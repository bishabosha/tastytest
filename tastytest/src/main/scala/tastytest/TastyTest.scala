package tastytest

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.nsc
import scala.util.{ Try, Success, Failure }

import dotty.tools.dotc

import java.nio.file.{ Files => JFiles, Paths => JPaths, Path => JPath }
import java.io.ByteArrayOutputStream
import java.{ lang => jl, util => ju }

import CommandLineParsers._
import Files._

object TastyTest {

  def tastytest(dottyLibrary: String, srcRoot: String, pkgName: String, run: Boolean, neg: Boolean, outDir: Option[String]): Try[Unit] = {
    val results = Map(
      "run" -> Tests.suite("run", run)(runSuite(dottyLibrary, srcRoot, pkgName, outDir)),
      "neg" -> Tests.suite("neg", neg)(negSuite(dottyLibrary, srcRoot, pkgName, outDir))
    )
    if (results.values.forall(_.isEmpty)) {
      printwarnln("No suites to run.")
    }
    successWhen(results.values.forall(_.getOrElse(true)))({
      val failures = results.filter(_._2.exists(!_))
      val str = if (failures.size == 1) "suite" else "suites"
      s"${failures.size} $str failed: ${failures.map(_._1).mkString(", ")}."
    })
  }

  def runSuite(dottyLibrary: String, srcRoot: String, pkgName: String, outDir: Option[String]): Try[Unit] = for {
    (pre, src2, src3) <- getSourcesRun(srcRoot/"run")
    out               <- outDir.fold(tempDir(pkgName))(dir)
    _                 <- scalacPos(out, dottyLibrary, srcRoot/"pre", pre:_*)
    _                 <- dotcPos(out, dottyLibrary, srcRoot/"src-3", src3:_*)
    _                 <- scalacPos(out, dottyLibrary, srcRoot/"src-2", src2:_*)
    testNames         <- visibleClasses(out, pkgName, src2:_*)
    _                 <- runMainOn(out, dottyLibrary, testNames:_*)
  } yield ()

  def negSuite(dottyLibrary: String, srcRoot: String, pkgName: String, outDir: Option[String]): Try[Unit] = for {
    (src2, src3) <- getSources(srcRoot/"neg", src2Filters = Set(Scala, Check))
    out          <- outDir.fold(tempDir(pkgName))(dir)
    _            <- dotcPos(out, dottyLibrary, srcRoot/"src-3", src3:_*)
    _            <- scalacNeg(out, dottyLibrary, src2:_*)
  } yield ()

  private def scalacPos(out: String, dottyLibrary: String, dir: String, sources: String*): Try[Unit] = {
    println(s"compiling sources in ${yellow(dir)} with scalac.")
    successWhen(scalac(out, dottyLibrary, sources:_*))("scalac failed to compile sources.")
  }

  private def scalacNeg(out: String, dottyLibrary: String, files: String*): Try[Unit] = {
    val errors = mutable.ArrayBuffer.empty[String]
    val unexpectedFail = mutable.ArrayBuffer.empty[String]
    val failMap = {
      val (sources, rest) = files.partition(ScalaFail.filter)
      sources.map({ s =>
        val check = s.stripSuffix(ScalaFail.name) + ".check"
        s -> rest.find(_ == check)
      }).toMap
    }
    if (failMap.isEmpty) {
      printwarnln(s"Warning: there are no source files marked as fail tests. (**/*${ScalaFail.name})")
    }
    for (source <- files.filter(Scala.filter)) {
      val buf = new StringBuilder(50)
      val compiled = {
        val byteArrayStream = new ByteArrayOutputStream(50)
        try {
          if (ScalaFail.filter(source)) {
            println(s"neg test ${cyan(source.stripSuffix(ScalaFail.name))} started")
          }
          val compiled = Console.withErr(byteArrayStream) {
            Console.withOut(byteArrayStream) {
              scalac(out, dottyLibrary, source)
            }
          }
          byteArrayStream.flush()
          buf.append(byteArrayStream.toString)
          compiled
        }
        finally {
          byteArrayStream.close()
        }
      }
      if (compiled) {
        if (failMap.contains(source)) {
          errors += source
          printerrln(s"ERROR: $source successfully compiled.")
        }
      }
      else {
        val output = buf.toString
        failMap.get(source) match {
          case None =>
            unexpectedFail += source
            System.err.println(output)
            printerrln(s"ERROR: $source did not compile when expected to. Perhaps it should match (**/*${ScalaFail.name})")
          case Some(checkFileOpt) =>
            checkFileOpt match {
              case Some(checkFile) =>
                processLines(checkFile) { stream =>
                  val checkLines  = stream.iterator().asScala.toSeq
                  val outputLines = Diff.splitIntoLines(output)
                  val diff        = Diff.compareContents(checkLines, outputLines)
                  if (diff.nonEmpty) {
                    errors += source
                    printerrln(s"ERROR: $source failed, unexpected output.\n$diff")
                  }
                }
              case None =>
                if (output.nonEmpty) {
                  errors += source
                  val diff = Diff.compareContents("", output)
                  printerrln(s"ERROR: $source failed, no check file found for unexpected output.\n$diff")
                }
            }
        }
      }
    }
    successWhen(errors.isEmpty && unexpectedFail.isEmpty) {
      if (unexpectedFail.nonEmpty) {
        val str = if (unexpectedFail.size == 1) "file" else "files"
        s"${unexpectedFail.length} $str did not compile when expected to: ${unexpectedFail.mkString(", ")}."
      }
      else {
        val str = if (errors.size == 1) "error" else "errors"
        s"${errors.length} $str. These sources either compiled or had an incorrect or missing check file: ${errors.mkString(", ")}."
      }
    }
  }

  private def scalac(out: String, dottyLibrary: String, sources: String*): Boolean = {
    sources.isEmpty || {
      val args = Array(
        "-d", out,
        "-classpath", classpaths(out, dottyLibrary),
        "-deprecation",
        "-Xfatal-warnings"
      ) ++ sources
      Try(nsc.Main.process(args)).getOrElse(false)
    }
  }

  private def dotcPos(out: String, dottyLibrary: String, dir: String, sources: String*): Try[Unit] = {
    println(s"compiling sources in ${yellow(dir)} with dotc.")
    val result = sources.isEmpty || {
      val args = Array(
        "-d", out,
        "-classpath", classpaths(out, dottyLibrary),
        "-deprecation",
        "-Xfatal-warnings"
      ) ++ sources
      Try(!dotc.Main.process(args).hasErrors).getOrElse(false)
    }
    successWhen(result)("dotc failed to compile sources.")
  }

  private def classpaths(paths: String*): String = paths.mkString(":")

  private def getSourceAsName(path: String): String =
    path.split(pathSep).last.stripSuffix(".scala")

  private def getSources(root: String, src2Filters: Set[SourceKind] = Set(Scala),
    src3Filters: Set[SourceKind] = Set(Scala)
  ): Try[(Seq[String], Seq[String])] = {
    import SourceKind.whitelist
    for {
      src2 <- getFiles(root/"src-2")
      src3 <- getFiles(root/"src-3")
    } yield (whitelist(src2Filters, src2:_*), whitelist(src3Filters, src3:_*))
  }

  private def getSourcesRun(root: String, preFilters: Set[SourceKind] = Set(Scala),
    src2Filters: Set[SourceKind] = Set(Scala), src3Filters: Set[SourceKind] = Set(Scala)
  ): Try[(Seq[String], Seq[String], Seq[String])] = {
    import SourceKind.whitelist
    for {
      pre          <- getFiles(root/"pre")
      (src2, src3) <- getSources(root, src2Filters, src3Filters)
    } yield (whitelist(preFilters, pre:_*), src2, src3)
  }

  private def visibleClasses(classpath: String, pkgName: String, src2: String*): Try[Seq[String]] = Try {
    val classes = {
      val matcher = globMatcher(
        s"$classpath/${if (pkgName.isEmpty) "" else pkgName.*->/}Test*.class"
      )
      val visibleTests = src2.map(getSourceAsName)
      val addPkg: String => String = if (pkgName.isEmpty) identity else pkgName + "." + _
      val prefix = if (pkgName.isEmpty) "" else pkgName.*->/
      val cp = JPaths.get(classpath).normalize
      def nameFromClass(path: JPath) = {
        path.subpath(cp.getNameCount, path.getNameCount)
            .normalize
            .toString
            .stripPrefix(prefix)
            .stripSuffix(".class")
      }
      var stream: ju.stream.Stream[JPath] = null
      try {
        stream = JFiles.walk(cp)
        stream.filter(p => !JFiles.isDirectory(p) && matcher.matches(p))
              .map(_.normalize)
              .iterator
              .asScala
              .drop(1) // drop the classpath itself
              .map(nameFromClass)
              .filter(visibleTests.contains)
              .map(addPkg)
              .toSeq
      }
      finally if (stream != null) {
        stream.close()
      }
    }
    if (classes.isEmpty) printwarnln("Warning: found no test classes.")
    classes
  }

  private def successWhen(cond: Boolean)(ifFalse: => String): Try[Unit] =
    Option.when(cond)(()).failOnEmpty(new TestFailure(ifFalse))

  private def runMainOn(out: String, dottyLibrary: String, tests: String*): Try[Unit] = {
    def runTests(errors: mutable.ArrayBuffer[String], runner: Runner): Try[Unit] = Try {
      for (test <- tests) {
        val (pkgs, name) = {
          val names = test.split('.')
          names.init.mkString(".") -> names.last
        }
        println(s"run suite ${if (pkgs.nonEmpty) pkgs + '.' else ""}${cyan(name)} started")
        runner.run(test) match {
          case Success(output) =>
            val diff = Diff.compareContents("Suite passed!", output)
            if (diff.nonEmpty) {
              errors += test
              printerrln(s"ERROR: $test failed, unexpected output.\n$diff")
            }
          case Failure(err) =>
            errors += test
            printerrln(s"ERROR: $test failed: ${err.getMessage}")
        }
      }
    }
    for {
      runnerPath  <- Runner.compile(dottyLibrary)(scalacPos)
      runner      <- Runner.loadFrom(classpaths(out, dottyLibrary, runnerPath))
      errors      =  mutable.ArrayBuffer.empty[String]
      _           <- runTests(errors, runner)
      _           <- successWhen(errors.isEmpty)({
                    val str = if (errors.size == 1) "error" else "errors"
                    s"${errors.length} $str. Fix ${errors.mkString(", ")}."
                  })
    } yield ()
  }

  private val helpText: String = """|# TASTy Test Help
  |
  |This runner can be used to test compilation and runtime behaviour of Scala 2 sources that depend on sources compiled with Scala 3.
  |
  |The following arguments are available to TASTy Test:
  |
  |  -help                            Display this help.
  |  -run                             Perform the run test.
  |  -neg                             Perform the neg test.
  |  --dotty-library  <paths>         Paths separated by `:`, the classpath for the dotty library.
  |  --src            <path=.>        The path that contains all compilation sources across test kinds.
  |  --out            <path=.>        output for classpaths, optional.
  |  --package        <pkg=tastytest> The package containing run tests.""".stripMargin

  def run(args: Seq[String]): Boolean = process(args).fold(
    err => {
      printerrln(s"ERROR: ${err.getClass.getName}: ${err.getMessage}")
      true
    },
    _ => false
  )

  def process(implicit args: Seq[String]): Try[Unit] = {
    if (booleanArg("-help")) {
      Success(println(helpText))
    }
    else for {
      dottyLibrary  <- requiredArg("--dotty-library")
      srcRoot       =  optionalArg("--src", currentDir)
      pkgName       =  optionalArg("--package", "tastytest")
      run           =  booleanArg("-run")
      neg           =  booleanArg("-neg")
      out           =  findArg("--out")
      _             <- tastytest(dottyLibrary, srcRoot, pkgName, run, neg, out)
    } yield ()
  }

  def main(args: Array[String]): Unit = sys.exit(if (run(args.toList)) 1 else 0)
}
