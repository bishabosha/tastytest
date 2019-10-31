package tastytest

import scala.language.implicitConversions

import scala.collection.mutable
import scala.io.{ Source, BufferedSource }
import scala.jdk.CollectionConverters._
import scala.reflect.internal.util.ScalaClassLoader
import scala.reflect.runtime.ReflectionUtils
import scala.tools.nsc
import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal

import dotty.tools.dotc
import dotc.reporting.{ Reporter => DottyReporter }

import java.nio.file.{ Files, Paths, Path, DirectoryStream, FileSystems }
import java.io.{ OutputStream, ByteArrayOutputStream }
import java.{ lang => jl, util => ju }
import jl.reflect.Modifier

object TastyTest {

  def tastytest(dottyLibrary: String, srcRoot: String, pkgName: String, run: Boolean, neg: Boolean, outDir: Option[String]): Try[Unit] = {
    val results = Map(
      "run" -> Tests.suite("run", run)(
        for {
          (pre, src2, src3) <- getSources(srcRoot/"run")
          out               <- outDir.fold(tempDir(pkgName))(dir)
          _                 <- scalacPos(out, dottyLibrary, pre:_*)
          _                 <- dotcPos(out, dottyLibrary, src3:_*)
          _                 <- scalacPos(out, dottyLibrary, src2:_*)
          testNames         <- visibleClasses(out, pkgName, src2:_*)
          _                 <- runMainOn(out, dottyLibrary, testNames:_*)
        } yield ()
      ),
      "neg" -> Tests.suite("neg", neg)(
        for {
          (pre, src2, src3) <- getSources(srcRoot/"neg", src2Filters = Set(Scala, Check))
          out               <- outDir.fold(tempDir(pkgName))(dir)
          _                 <- scalacPos(out, dottyLibrary, pre:_*)
          _                 <- dotcPos(out, dottyLibrary, src3:_*)
          _                 <- scalacNeg(out, dottyLibrary, src2:_*)
        } yield ()
      )
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

  object Tests {

    def printSummary(suite: String, result: Try[Unit]) = result match {
      case Success(_)   => printsuccessln(s"$suite suite passed!")
      case Failure(err) => printerrln(s"ERROR: $suite suite failed: ${err.getMessage}")
    }

    def suite(name: String, willRun: Boolean)(runner: => Try[Unit]): Option[Boolean] = {
      if (willRun) {
        println(s"Performing suite $name")
        val result = runner
        printSummary(name, result)
        Some(result.isSuccess)
      }
      else {
        None
      }
    }

  }

  private def scalacPos(out: String, dottyLibrary: String, sources: String*): Try[Unit] =
    successWhen(scalac(out, dottyLibrary, sources:_*))("scalac failed to compile sources.")

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
    for (source <- files.filter(_.endsWith(".scala"))) {
      val buf = new StringBuilder(50)
      val compiled = {
        val byteArrayStream = new ByteArrayOutputStream(50)
        try {
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
                var checkfile: java.util.stream.Stream[String] = null
                try {
                  checkfile       = Files.lines(Paths.get(checkFile))
                  val checkLines  = checkfile.iterator().asScala.toSeq
                  val outputLines = Diff.splitIntoLines(output)
                  val diff        = Diff.compareContents(checkLines, outputLines)
                  if (diff.nonEmpty) {
                    errors += source
                    printerrln(s"ERROR: $source failed, unexpected output.\n$diff")
                  }
                }
                finally if (checkfile != null) {
                  checkfile.close()
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

  // TODO call it directly when we can unpickle overloads
  private[this] lazy val dotcProcess: Array[String] => Boolean = {
    val process = classOf[dotc.Driver].getMethod("process", classOf[Array[String]])
    args => Try(!process.invoke(dotc.Main, args).asInstanceOf[DottyReporter].hasErrors).getOrElse(false)
  }

  private def dotcPos(out: String, dottyLibrary: String, sources: String*): Try[Unit] = {
    val result = sources.isEmpty || {
      val args = Array(
        "-d", out,
        "-classpath", classpaths(out, dottyLibrary),
        "-deprecation",
        "-Xfatal-warnings"
      ) ++ sources
      dotcProcess(args)
    }
    successWhen(result)("dotc failed to compile sources.")
  }

  private def classpaths(paths: String*): String = paths.mkString(":")
  private def path(part: String, parts: String*): String = (part +: parts).mkString(pathSep)

  private def optionalArg(arg: String, default: => String)(implicit args: Seq[String]): String =
    findArg(arg).getOrElse(default)

  private def requiredArg(arg: String)(implicit args: Seq[String]): Try[String] =
    failOnEmpty(findArg(arg))(s"please provide argument: $arg")

  private def booleanArg(arg: String)(implicit args: Seq[String]): Boolean =
    args.contains(arg)

  private def findArg(arg: String)(implicit args: Seq[String]): Option[String] =
    args.sliding(2).filter(_.length == 2).find(_.head == arg).map(_.last)

  private def getSourceAsName(path: String): String =
    path.split(pathSep).last.stripSuffix(".scala")

  sealed abstract class SourceKind(val name: String)(val filter: String => Boolean = _.endsWith(name))
  case object NoSource extends SourceKind("")(filter = _ => false)
  case object Scala extends SourceKind(".scala")()
  case object ScalaFail extends SourceKind("_fail.scala")()
  case object Check extends SourceKind(".check")()

  private def whitelist(kinds: Set[SourceKind], paths: String*): Seq[String] =
    if (kinds.isEmpty) Nil
    else paths.filter(kinds.foldLeft(NoSource.filter)((filter, kind) => p => kind.filter(p) || filter(p)))

  private def getSources(root: String, preFilters: Set[SourceKind] = Set(Scala),
    src2Filters: Set[SourceKind] = Set(Scala), src3Filters: Set[SourceKind] = Set(Scala)
  ): Try[(Seq[String], Seq[String], Seq[String])] = for {
    pre  <- getFiles(root/"pre")
    src2 <- getFiles(root/"src-2")
    src3 <- getFiles(root/"src-3")
  } yield (whitelist(preFilters, pre:_*), whitelist(src2Filters, src2:_*), whitelist(src3Filters, src3:_*))

  private def getFiles(dir: String): Try[Seq[String]] = Try {
    var stream: java.util.stream.Stream[Path] = null
    try {
      stream = Files.walk(Paths.get(dir))
      val files = {
        stream.filter(!Files.isDirectory(_))
              .map(_.normalize.toString)
              .iterator
              .asScala
              .toSeq
      }
      if (files.isEmpty) printwarnln(s"Warning: $dir is empty.")
      files
    } finally {
      if (stream != null) {
        stream.close()
      }
    }
  }

  def use[T](resource: String)(op: jl.Iterable[String] => Try[T]): Try[T] = Try {
    var source: BufferedSource = null
    try {
      source = Source.fromResource(resource)
      op(() => source.getLines.asJava)
    }
    finally if (source != null) {
      source.close()
    }
  }.flatten

  private def visibleClasses(classpath: String, pkgName: String, src2: String*): Try[Seq[String]] = Try {
    val classes = {
      val matcher = FileSystems.getDefault.getPathMatcher(
        s"glob:$classpath/${if (pkgName.isEmpty) "" else pkgName.*->/}Test*.class"
      )
      val visibleTests = src2.map(getSourceAsName)
      val addPkg: String => String = if (pkgName.isEmpty) identity else pkgName + "." + _
      val prefix = if (pkgName.isEmpty) "" else pkgName.*->/
      val cp = Paths.get(classpath).normalize
      def nameFromClass(path: Path) = {
        path.subpath(cp.getNameCount, path.getNameCount)
            .normalize
            .toString
            .stripPrefix(prefix)
            .stripSuffix(".class")
      }
      var stream: ju.stream.Stream[Path] = null
      try {
        stream = Files.walk(cp)
        stream.filter(p => !Files.isDirectory(p) && matcher.matches(p))
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

  implicit final class PathOps(val s: String) extends AnyVal {
    @inline final def / (part: String): String = path(s, part)
    @inline final def / (parts: Seq[String]): String = path(s, parts:_*)
    @inline final def **/ : IndexedSeq[String] = s.split(raw"\.").toIndexedSeq
    @inline final def *->/ : String = s.replace(raw"\.", pathSep) + "/"
  }

  private def tempDir(dir: String): Try[String] = Try(Files.createTempDirectory(dir)).map(_.toString)
  private def dir(dir: String): Try[String] = Try {
    val path = Paths.get(dir)
    if (Files.isDirectory(path)) {
      Success(path.normalize.toString)
    }
    else {
      Failure(new IllegalArgumentException(s"$path is not a directory."))
    }
  }.flatten

  private def successWhen(cond: Boolean)(ifFalse: => String): Try[Unit] =
    failOnEmpty(Option.when(cond)(()))(ifFalse)

  private def failOnEmpty[A](opt: Option[A])(ifEmpty: => String): Try[A] =
    opt.toRight(new IllegalStateException(ifEmpty)).toTry

  private def classloadFrom(cp: String): Try[ScalaClassLoader] =
    for (classpath <- Try(cp.split(":").filter(_.nonEmpty).map(Paths.get(_).toUri.toURL)))
    yield ScalaClassLoader.fromURLs(classpath.toIndexedSeq)

  private final class Runner private (classloader: ScalaClassLoader) {

    private[this] val RunnerClass =
      try Class.forName(Runner.name, true, classloader)
      catch {
        case _:SecurityException | _:ClassNotFoundException =>
          throw new IllegalArgumentException(s"no ${Runner.name} in classloader")
      }

    private[this] val Runner_run = {
      val run = RunnerClass.getMethod("run", classOf[String], classOf[OutputStream], classOf[OutputStream])
      if (!Modifier.isStatic(run.getModifiers))
        throw new IllegalStateException(s"${Runner.name}.run is not static.")
      run
    }

    private def run(name: String, out: OutputStream, err: OutputStream): Try[Unit] =
      try classloader.asContext {
        Runner_run.invoke(null, name, out, err)
        Success(())
      }
      catch { case NonFatal(ex) => Failure(ReflectionUtils.unwrapThrowable(ex)) }

    def run(name: String): Try[String] = {
      val byteArrayStream = new ByteArrayOutputStream(50)
      try {
        val result = run(name, byteArrayStream, byteArrayStream)
        byteArrayStream.flush()
        result.map(_ => byteArrayStream.toString)
      }
      finally {
        byteArrayStream.close()
      }
    }
  }

  private object Runner {

    def compile(dottyLibrary: String): Try[String] = for {
      (pkg, src) <- writeRunner
      _          <- dotcPos(pkg, dottyLibrary, src)
    } yield pkg

    private def writeRunner: Try[(String, String)] = for {
      pkg  <- tempDir("tastytest.internal")
      file <- createFile(pkg)
      path <- writeFile(file)
    } yield (pkg.toString, path.toString)

    def loadFrom(classloader: ScalaClassLoader): Try[Runner] = Try(new Runner(classloader))

    private val name = "tastytest.internal.Runner"

    private val path = "tastytest.internal/Runner.scala"

    private def writeFile(file: Path): Try[Path] = use(path)(lines => Try(Files.write(file, lines)))

    private def createFile(dir: String): Try[Path] = Try(Files.createFile(Paths.get(dir, "Runner.scala")))
  }

  private def runMainOn(out: String, dottyLibrary: String, tests: String*): Try[Unit] = {
    def runTests(errors: mutable.ArrayBuffer[String], runner: Runner): Try[Unit] = Try {
      for (test <- tests) runner.run(test) match {
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
    for {
      runnerPath  <- Runner.compile(dottyLibrary)
      classloader <- classloadFrom(classpaths(out, dottyLibrary, runnerPath))
      runner      <- Runner.loadFrom(classloader)
      errors      =  mutable.ArrayBuffer.empty[String]
      _           <- runTests(errors, runner)
      _           <- successWhen(errors.isEmpty)({
                    val str = if (errors.size == 1) "error" else "errors"
                    s"${errors.length} $str. Fix ${errors.mkString(", ")}."
                  })
    } yield ()
  }

  private val pathSep: String = FileSystems.getDefault.getSeparator

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
  |  --package        <pkg=tastytest> The package containing run tests.
  |
  |* This runner should be invoked with the `scala-compiler` module on the classpath, easily acheived by using the `scala` shell command.
  |* During compilation of test sources, and during run test execution, `--dotty-library` is on the classpath.
  |* TASTy Test currently supports run and neg tests.
  |* run tests execute as follows:
  |  1. Compile sources in `$src$/run/pre/**` with the Scala 2 compiler, to be shared accross both compilers.
  |  2. Compile sources in `$src$/run/src-3/**` with the Dotty compiler in a separate process, using `--dotty-compiler` as the JVM classpath.
  |     - Classes compiled in (1) are now on the classpath.
  |  3. Compile sources in `$src$/run/src-2/**` with the Scala 2 compiler.
  |     - Classes compiled in (1) and (2) are now on the classpath.
  |  4. Classes with name `$package$Test*` are assumed to be test cases and their main methods are executed sequentially.
  |     - A successful test should print the single line `Suite passed!` and not have any runtime exceptions.
  |     - The class will not be executed if there is no source file in `$src$/run/src-2/**` that matches the simple name of the class.
  |* neg tests execute as follows:
  |  1. Compile sources in `$src$/neg/pre/**` with the Scala 2 compiler, to be shared accross both compilers.
  |  2. Compile sources in `$src$/neg/src-3/**` with the Dotty compiler in a separate process, using `--dotty-compiler` as the JVM classpath.
  |     - Classes compiled in (1) are now on the classpath.
  |  3. Compile sources in `$src$/neg/src-2/**` with the Scala 2 compiler.
  |     - Classes compiled in (1) and (2) are now on the classpath.
  |     - A source file matching `<path>/<name>_fail.scala` succeeds a test if it fails compilation and all compiler output matches a checkfile of name `<path>/<name>.check`
  |
  |Note: Failing tests without a fix should be put in a sibling directory, such as `suspended`, to document that they are incompatible at present.""".stripMargin

  def run(args: Seq[String]): Boolean = process(args).fold(
    err => {
      val prefix = err match {
        case _: IllegalStateException => ""
        case _                        => s" ${err.getClass.getSimpleName}:"
      }
      printerrln(s"ERROR:$prefix ${err.getMessage}")
      true
    },
    _ => false
  )

  def printerrln(str: String): Unit = System.err.println(Console.RED + str + Console.RESET)
  def printwarnln(str: String): Unit = System.err.println(Console.YELLOW + str + Console.RESET)
  def printsuccessln(str: String): Unit = System.err.println(Console.GREEN + str + Console.RESET)

  def process(implicit args: Seq[String]): Try[Unit] = {
    if (booleanArg("-help")) {
      Success(println(helpText))
    }
    else for {
      dottyLibrary  <- requiredArg("--dotty-library")
      srcRoot       =  optionalArg("--src", FileSystems.getDefault.getPath(".").toString)
      pkgName       =  optionalArg("--package", "tastytest")
      run           =  booleanArg("-run")
      neg           =  booleanArg("-neg")
      out           =  findArg("--out")
      _             <- tastytest(dottyLibrary, srcRoot, pkgName, run, neg, out)
    } yield ()
  }

  def main(args: Array[String]): Unit = sys.exit(if (run(args.toList)) 1 else 0)
}
