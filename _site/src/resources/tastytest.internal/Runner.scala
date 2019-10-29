package tastytest.internal

object Runner
  def run(main: java.lang.reflect.Method, out: java.io.OutputStream, err: java.io.OutputStream): Unit =
    Console.withOut(out) { Console.withErr(err) { main.invoke(null, Array.empty[String]) } }""".stripMargin
