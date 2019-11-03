package tastytest

import scala.util.{ Try, Success, Failure }
import scala.util.control.NonFatal
import scala.reflect.internal.util.ScalaClassLoader
import scala.reflect.runtime.ReflectionUtils

import java.nio.file.{ Files => JFiles, Paths => JPaths, Path, DirectoryStream, FileSystems }
import java.io.{ OutputStream, ByteArrayOutputStream, FileNotFoundException }
import java.lang.reflect.Modifier

import Files._

final class Runner private (classloader: ScalaClassLoader) {

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

object Runner {

  def compile(dottyLibrary: String)(dotc: (String, String, Seq[String]) => Try[Unit]): Try[String] = for {
    (pkg, src) <- writeRunner
    _          <- dotc(pkg, dottyLibrary, src :: Nil)
  } yield pkg

  private def writeRunner: Try[(String, String)] = for {
    pkg  <- tempDir("tastytest.internal")
    file <- createFile(pkg)
    path <- writeFile(file)
  } yield (pkg.toString, path.toString)

  private def classloadFrom(classpath: String): Try[ScalaClassLoader] =
    for (classpaths <- Try(classpath.split(":").filter(_.nonEmpty).map(JPaths.get(_).toUri.toURL)))
    yield ScalaClassLoader.fromURLs(classpaths.toIndexedSeq)

  def loadFrom(classpath: String): Try[Runner] =
    for {
      classloader <- classloadFrom(classpath)
      runner      <- Try(new Runner(classloader))
    } yield runner

  private val name = "tastytest.internal.Runner"

  private val path = "tastytest.internal/Runner.scala"

  private def writeFile(file: Path): Try[Path] = use(path)(lines => Try(JFiles.write(file, lines)))

  private def createFile(dir: String): Try[Path] = Try(JFiles.createFile(JPaths.get(dir, "Runner.scala")))
}
