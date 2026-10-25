import sbtcrossproject.CrossPlugin.autoImport.crossProject

val scala3 = "3.3.8"
val pklVersion = "0.31.1"

ThisBuild / organization := "org.pkl-lang"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3

// Needed by scalafix (RemoveUnused, OrganizeImports) on every project.
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
// Required by OrganizeImports (removeUnused); harmless everywhere, including JS/Native.
ThisBuild / scalacOptions += "-Wunused:imports"

// Pure rendering core: no pkl-core dependency, cross-built for JVM, Scala.js and Scala Native.
lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core"))
  .settings(
    name := "pkl-codegen-scala-core",
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    // munit 1.1.1 was built against an older test-interface; the Native plugin is newer.
    libraryDependencySchemes += "org.scala-native" % "test-interface_native0.5_3" % "always"
  )
  .nativeSettings(
    // sbt 2 runs `test` incrementally, and with the external-process Native runner that yields
    // vacuous 0-test passes. Force explicit execution of the whole suite instead.
    Test / test := (Test / testOnly).toTask(" org.pkl.codegen.scala.*").value
  )

// JVM-only front end: pkl-core schema adapter + CLI. Plugin seam lives here.
lazy val pkl = project
  .in(file("pkl"))
  .dependsOn(core.jvm)
  .settings(
    name := "pkl-codegen-scala",
    libraryDependencies ++= Seq(
      "org.pkl-lang" % "pkl-core" % pklVersion,
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
