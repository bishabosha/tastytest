package tastytest.internal

import java.lang.reflect.Modifier

object Runner
  def run(name: String, out: java.io.OutputStream, err: java.io.OutputStream): Unit =
    val objClass = Class.forName(name, true, getClass.getClassLoader)
    val main     = objClass.getMethod("main", classOf[Array[String]])
    if !Modifier.isStatic(main.getModifiers)
      throw NoSuchMethodException(name + ".main is not static")
    Console.withOut(out) {
      Console.withErr(err) {
        main.invoke(null, Array.empty[String])
      }
    }
