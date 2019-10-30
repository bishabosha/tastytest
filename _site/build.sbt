val dottyVersion  = "0.20.0-bin-20191028-d06a5ff-NIGHTLY" // contains scala-asm 7.0 dependency
val scalacRelease = "2.13.2-bin-SNAPSHOT" // publish a local release of scala compiler with the TASTy Reader

lazy val root = project
  .in(file("."))
  .settings(
    name := "tasty-test",
    version := "0.1.0-SNAPSHOT",
    organization := "ch.epfl.scala",

    scalaVersion := dottyVersion,

    libraryDependencies += "com.novocode"                   %  "junit-interface"  % "0.11"          % "test",
    libraryDependencies += "com.googlecode.java-diff-utils" %  "diffutils"        % "1.3.0",
    libraryDependencies += "org.scala-lang"                 %  "scala-library"    % scalacRelease,
    libraryDependencies += "org.scala-lang"                 %  "scala-compiler"   % scalacRelease,
    libraryDependencies += "ch.epfl.lamp"                   %% "dotty-compiler"   % dottyVersion
  )
