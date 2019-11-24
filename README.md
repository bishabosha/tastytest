## TastyTest Standalone

This is a standalone bootstrapped fork of the TastyTest framework found [here](https://github.com/scalacenter/scala/blob/tasty_reader/src/tastytest/scala/tools/tastytest), it compiles with a version of scalac containing the [TASTy Reader for Scala 2](https://scala.epfl.ch/projects.html#tastyScala2).

### Usage

- [get coursier](https://get-coursier.io)
- `cd scalac/scala && sbt publishLocal && cd ../..` to publish the version of scala containing the TASTy Reader.
- compile TastyTest with `sbt tastytest/publishLocal`.
- run with `coursier launch ch.epfl.scala:tasty-test_2.13:0.1.0-SNAPSHOT -- -help`
