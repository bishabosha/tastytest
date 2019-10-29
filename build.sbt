val dottyVersion  = dottyLatestNightlyBuild.get
val scalacRelease = "2.13.2-bin-SNAPSHOT" // publish a local release of scala compiler with the TASTy Reader

lazy val root = project
  .in(file("."))
  .settings(
    name := "tasty-test",
    version := "0.1.0-SNAPSHOT",
    organization := "ch.epfl.scala",

    scalaVersion := dottyVersion,

    libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",

    libraryDependencies += "org.scala-lang" %  "scala-library"  % scalacRelease,
    libraryDependencies += "org.scala-lang" %  "scala-compiler" % scalacRelease,
    libraryDependencies += "ch.epfl.lamp"   %% "dotty-compiler" % dottyVersion
  )
