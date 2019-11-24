val dottyVersion       = "0.21.0-bin-20191107-3cb93f3-NIGHTLY" // contains tasty version 18
val tastyReaderVersion = "2.13.2-bin-SNAPSHOT" // publish a local release of https://github.com/scalacenter/scala/tree/tasty_reader
val tastytestVersion   = "0.1.0-SNAPSHOT"

val commonDependencies = Seq(
  "com.novocode"                   % "junit-interface"     % "0.11"               % "test",
  "com.googlecode.java-diff-utils" % "diffutils"           % "1.3.0",
  "org.scala-lang"                 % "scala-compiler"      % tastyReaderVersion,
  "ch.epfl.lamp"                   % "dotty-compiler_0.21" % dottyVersion,
)

val CommonSettings = Seq(
  version      := tastytestVersion,
  organization := "ch.epfl.scala",
  libraryDependencies ++= commonDependencies,
  scalaVersion := tastyReaderVersion,
)

lazy val root = (project in file("."))
  .aggregate(tastytest, example)
  .settings(
    sourceDirectories in Compile := Nil,
    sourceDirectories in Test := Nil,
  )

lazy val tastytest = project
  .in(file("tastytest"))
  .settings(
    name := "tasty-test",
    CommonSettings
  )

lazy val example = project
  .in(file("example"))
  .dependsOn(tastytest)
  .settings(
    name := "tasty-test-example",
    CommonSettings,
    fork in Test := true,
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v"),
    testOptions in Test += Tests.Argument(
      s"-Dtastytest.dotty-library=${(Compile / externalDependencyClasspath).value.map(_.data.toString).mkString(":")}",
      s"-Dtastytest.src=${(baseDirectory in ThisProject).value/"test"/"tasty"}",
      s"-Dtastytest.packageName=${"tastytest"}"
    ),
  )
