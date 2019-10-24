import scala.language.implicitConversions

import scala.sys.process._
import java.nio.file.{Files, Paths, Path, DirectoryStream, FileSystems}
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.util.{Try, Success, Failure}
import scala.util.Properties

import scala.reflect.internal.util.ScalaClassLoader
import scala.tools.nsc
import dotty.tools.dotc
import java.util.stream.Collectors
import java.lang.reflect.Modifier
import java.lang.reflect.Method

object TastyTest {

  def tastytest(dottyLibrary: String, srcRoot: String, pkgName: String, run: Boolean, neg: Boolean, outDir: Option[String]): Try[Unit] = {
    val results = Map(
      "run" -> Tests.suite("run", run)(
        for {
          (pre, src2, src3) <- getSources(srcRoot/"run")
          preClean          =  whitelist(paths = pre:_*)
          src2Clean         =  whitelist(paths = src2:_*)
          src3Clean         =  whitelist(paths = src3:_*)
          out               <- outDir.fold(tempDir(pkgName))(dir)
          _                 <- scalacPos(out, dottyLibrary, preClean:_*)
          _                 <- dotcPos(out, dottyLibrary, src3Clean:_*)
          _                 <- scalacPos(out, dottyLibrary, src2Clean:_*)
          testNames         <- visibleClasses(out, pkgName, src2Clean:_*)
          _                 <- runMainOn(out, dottyLibrary, testNames:_*)
        } yield ()
      ),
      "neg" -> Tests.suite("neg", neg)(
        for {
          (pre, src2, src3) <- getSources(srcRoot/"neg")
          preClean          =  whitelist(paths = pre:_*)
          src2Clean         =  whitelist(check = true, paths = src2:_*)
          src3Clean         =  whitelist(paths = src3:_*)
          out               <- outDir.fold(tempDir(pkgName))(dir)
          _                 <- scalacPos(out, dottyLibrary, preClean:_*)
          _                 <- dotcPos(out, dottyLibrary, src3Clean:_*)
          _                 <- scalacNeg(out, dottyLibrary, src2Clean:_*)
        } yield ()
      )
    )
    successWhen(results.values.forall(identity))({
      val failures = results.filter(!_._2)
      val str = if (failures.size == 1) "suite" else "suites"
      s"${failures.size} $str failed: ${failures.map(_._1).mkString(", ")}."
    })
  }

  object Tests {

    def printSummary(suite: String, result: Try[Unit]) = result match {
      case Success(_)   => printsuccessln(s"$suite suite passed!")
      case Failure(err) => printerrln(s"ERROR: $suite suite failed: ${err.getClass.getSimpleName} ${err.getMessage}")
    }

    def suite(name: String, willRun: Boolean)(runner: => Try[Unit]): Boolean = {
      if (willRun)
        println(s"Performing suite $name")
        val result = runner
        printSummary(name, result)
        result.isSuccess
      else
        true
    }

  }

  private def scalacPos(out: String, dottyLibrary: String, sources: String*): Try[Unit] =
    successWhen(scalac(out, dottyLibrary, sources:_*))("scalac failed to compile sources.")

  private def scalacNeg(out: String, dottyLibrary: String, files: String*): Try[Unit] = {
    val errors = mutable.ArrayBuffer.empty[String]
    val unexpectedFail = mutable.ArrayBuffer.empty[String]
    val failMap = {
      val (sources, rest) = files.partition(_.endsWith(negScalaSource))
      sources.map({ s =>
        val check = s.stripSuffix(negScalaSource) + ".check"
        s -> rest.find(_ == check)
      }).toMap
    }
    if (failMap.isEmpty) {
      printwarnln(s"Warning: there are no source files marked as fail tests. (**/*$negScalaSource)")
    }
    for (source <- files.filter(_.endsWith(".scala"))) {
      val buf = new StringBuilder(50)
      val compiled = {
        val byteArrayStream = new java.io.ByteArrayOutputStream(50)
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
            printerrln(s"ERROR: $source did not compile when expected to. Perhaps it should match (**/*$negScalaSource)")
          case Some(checkFileOpt) =>
            checkFileOpt match {
              case Some(checkFile) =>
                var lines: java.util.stream.Stream[String] = null
                try {
                  lines = Files.lines(Paths.get(checkFile))
                  val check = lines.iterator().asScala.mkString("", System.lineSeparator, System.lineSeparator)
                  if (check != output) {
                    errors += source
                    printerrln(s"ERROR: $source failed, unexpected output <<<;OUTPUT;\n${output}\n;OUTPUT;<<<;EXPECT;\n${check}\n;EXPECT;")
                  }
                }
                finally if (lines != null) {
                  lines.close()
                }
              case None =>
                if (output.nonEmpty) {
                  errors += source
                  printerrln(s"ERROR: $source failed, no check file found for unexpected output <<<;OUTPUT;\n${output}\n;OUTPUT;")
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
        "-classpath", classpaths(out, dottyLibrary)
      ) ++ sources
      nsc.Main.process(args)
    }
  }

  private def dotcPos(out: String, dottyLibrary: String, sources: String*): Try[Unit] = {
    val result = sources.isEmpty || {
      val args = Array(
        "-d", out,
        "-classpath", classpaths(out, dottyLibrary)
      ) ++ sources
      !dotc.Main.process(args).hasErrors
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

  private val negScalaSource = "_fail.scala"
  private def isScala(path: String) = path.endsWith(".scala")
  private def isCheck(path: String) = path.endsWith(".check")

  private def whitelist(check: Boolean = false, paths: String*) =
    paths.filter(if (check) isScala || isCheck else isScala)

  private def getSources(root: String): Try[(Seq[String], Seq[String], Seq[String])] = for {
    pre  <- getFiles(root/"pre")
    src2 <- getFiles(root/"src-2")
    src3 <- getFiles(root/"src-3")
  } yield (pre, src2, src3)

  private def getFiles(dir: String): Try[Seq[String]] = Try {
    var stream: java.util.stream.Stream[Path] = null
    try {
      stream = Files.walk(Paths.get(dir))
      val files = {
        stream.filter(!Files.isDirectory(_))
              .map(_.normalize.toString)
              .collect(Collectors.toList[String])
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
      var stream: java.util.stream.Stream[Path] = null
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

  implicit final class PredicateOps[A](val p: A => Boolean) extends AnyVal {
    @inline final def || (q: A => Boolean): A => Boolean = a => p(a) || q(a)
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
    yield ScalaClassLoader.fromURLs(classpath)

  private final class Runner private (classloader: ScalaClassLoader) {

    private[this] val clazz =
      try Class.forName(Runner.name, true, classloader)
      catch
        case err: (SecurityException | ClassNotFoundException) =>
          throw IllegalArgumentException(s"no ${Runner.name} in classloader")

    private[this] val Runner_run =
      clazz.getMethod("run", classOf[Method], classOf[java.io.OutputStream], classOf[java.io.OutputStream])

    def run(name: String): Try[String] = {
      def kernel(out: java.io.OutputStream, err: java.io.OutputStream): Try[Unit] = Try {
        val objClass = Class.forName(name, true, classloader)
        val main     = objClass.getMethod("main", classOf[Array[String]])
        if !Modifier.isStatic(main.getModifiers)
          throw NoSuchMethodException(name + ".main is not static")
        classloader.asContext[Unit] {
          Runner_run.invoke(null, main, out, err)
        }
      }
      val byteArrayStream = new java.io.ByteArrayOutputStream(50)
      try {
        val result = kernel(byteArrayStream, byteArrayStream)
        byteArrayStream.flush()
        result.map(_ => byteArrayStream.toString)
      }
      finally {
        byteArrayStream.close()
      }
    }
  }

  private object Runner {

    def compile(dottyLibrary: String): Try[String] = for
      (pkg, src) <- writeRunner
      _          <- dotcPos(pkg, dottyLibrary, src)
    yield pkg

    private def writeRunner: Try[(String, String)] = for
      pkg  <- tempDir("tastytest.internal")
      file <- createFile(pkg)
      path <- writeFile(file)
    yield (pkg.toString, path.toString)

    def loadFrom(classloader: ScalaClassLoader): Try[Runner] = Try(Runner(classloader))

    private val name = "tastytest.internal.Runner"

    private val src: String =
    """|package tastytest.internal
       |
       |object Runner
       |  def run(main: java.lang.reflect.Method, out: java.io.OutputStream, err: java.io.OutputStream): Unit =
       |    Console.withOut(out) { Console.withErr(err) { main.invoke(null, Array.empty[String]) } }""".stripMargin

    private def writeFile(file: Path): Try[Path] = Try(Files.write(file, src.getBytes))

    private def createFile(dir: String): Try[Path] = Try(Files.createFile(Paths.get(dir, "Runner.scala")))
  }

  private def runMainOn(out: String, dottyLibrary: String, tests: String*): Try[Unit] = {
    def runTests(errors: mutable.ArrayBuffer[String], runner: Runner): Try[Unit] = Try {
      for test <- tests do
        runner.run(test) match
        case Success(output) =>
          if ("Suite passed!" != output.trim) {
            errors += test
            printerrln(s"ERROR: $test failed, unexpected output <<<;OUTPUT;\n${output}\n;OUTPUT;")
          }
        case Failure(err) =>
          errors += test
          printerrln(s"ERROR: $test failed: ${err.getMessage}")
    }
    for {
      instrumentors   <- Runner.compile(dottyLibrary)
      classloader     <- classloadFrom(classpaths(out, dottyLibrary, instrumentors))
      instrumentor    <- Runner.loadFrom(classloader)
      errors          =  mutable.ArrayBuffer.empty[String]
      _               <- runTests(errors, instrumentor)
      _               <- successWhen(errors.isEmpty)({
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
  |  -help                        Display this help.
  |  -run                         Perform the run test.
  |  -neg                         Perform the neg test.
  |  --dotty-library   <paths>    Paths separated by `:`, the classpath for the dotty library.
  |  --src             <path = .> The path that contains all compilation sources across test kinds.
  |  --out             <path = .> output for classpaths, optional.
  |  --package         <pkg : ""> The package containing run tests.
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
