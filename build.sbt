val dottyVersion = "0.20.0-bin-SNAPSHOT"

lazy val root = project
  .in(file("."))
  .settings(
    name := "tasty-test",
    version := "0.1.0-SNAPSHOT",
    organization := "ch.epfl.scala",

    scalaVersion := dottyVersion,

    libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",

    libraryDependencies += "org.scala-lang" % "scala-library" % "2.13.2-bin-SNAPSHOT",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.2-bin-SNAPSHOT",
    libraryDependencies += "ch.epfl.lamp" %% "dotty-compiler" % "0.20.0-bin-SNAPSHOT"
  )
