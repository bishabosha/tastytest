## TastyTest Standalone

This is a standalone bootstrapped fork of the TastyTest framework found [here](https://github.com/scalacenter/scala/blob/tasty_reader/src/tastytest/scala/tools/tastytest), it compiles with a version of scalac containing the [TASTy Reader for Scala 2](https://scala.epfl.ch/projects.html#tastyScala2).

An interesting fact about this fork is that it directly invokes the `dotty.tools.dotc.Main.process` method, and does not require JVM reflection: the right overload is selected based on signatures derived from TASTy.

### About

See how it works [here](https://github.com/scalacenter/scala/blob/tasty_reader/doc/internal/tastytest.md).

### Usage

- [get coursier](https://get-coursier.io)
- `sbt publishTastyReader` to publish the version of scala containing the TASTy Reader.
- compile TastyTest with `sbt tastytest/publishLocal`.
- run with `coursier launch ch.epfl.scala:tasty-test_2.13:0.1.0-SNAPSHOT -- -help`
