## TastyTest Standalone

This is a standalone fork of the TastyTest found [here](https://github.com/scalacenter/scala/blob/tasty_reader/test/tasty/TastyTest.scala), as an sbt project that compiles with a scala compiler containing the [TASTy Reader for Scala 2](https://github.com/scalacenter/scala/tree/tasty_reader).
To build it, you should use `sbt publishLocal` on the **most recent commit** of the TASTy reader with version `2.13.2-bin-SNAPSHOT`.

### Usage

- [get coursier](https://get-coursier.io)
- compile with `sbt publishLocal`.
- run with `coursier launch ch.epfl.scala:tasty-test_2.13:0.1.0-SNAPSHOT -- -help`
