val dottyVersion = "0.20.0-RC1"
val tastyReader  = "2.13.2-bin-SNAPSHOT" // publish a local release of https://github.com/scalacenter/scala/tree/tasty_reader

lazy val root = project
  .in(file("."))
  .settings(
    name := "tasty-test",
    version := "0.1.0-SNAPSHOT",
    organization := "ch.epfl.scala",

    scalaVersion := tastyReader,

    libraryDependencies += "com.novocode"                   % "junit-interface"     % "0.11"        % "test",
    libraryDependencies += "com.googlecode.java-diff-utils" % "diffutils"           % "1.3.0",
    libraryDependencies += "org.scala-lang"                 % "scala-library"       % tastyReader,
    libraryDependencies += "org.scala-lang"                 % "scala-compiler"      % tastyReader,
    libraryDependencies += "ch.epfl.lamp"                   % "dotty-compiler_0.20" % dottyVersion
  )
