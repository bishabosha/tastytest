package object tastytest {

import java.nio.file.FileSystems

import scala.util.Try

import Files.pathSep

  def printerrln(str: String): Unit = System.err.println(red(str))
  def printwarnln(str: String): Unit = System.err.println(yellow(str))
  def printsuccessln(str: String): Unit = System.err.println(green(str))

  def cyan(str: String): String = Console.CYAN + str + Console.RESET
  def yellow(str: String): String = Console.YELLOW + str + Console.RESET
  def red(str: String): String = Console.RED + str + Console.RESET
  def green(str: String): String = Console.GREEN + str + Console.RESET

  implicit final class PathOps(val s: String) extends AnyVal {
    @inline final def / (part: String): String = path(s, part)
    @inline final def / (parts: Seq[String]): String = path(s, parts:_*)
    @inline final def **/ : IndexedSeq[String] = s.split(raw"\.").toIndexedSeq
    @inline final def *->/ : String = s.replace(raw"\.", pathSep) + "/"
  }

  private def path(part: String, parts: String*): String = (part +: parts).mkString(pathSep)

  implicit final class OptionOps[A](val opt: Option[A]) extends AnyVal {
    @inline final def failOnEmpty(ifEmpty: => Throwable): Try[A] = opt.toRight(ifEmpty).toTry
  }

}
