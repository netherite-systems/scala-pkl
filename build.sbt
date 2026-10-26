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
      "org.scala-lang" %% "scala3-compiler" % scala3 % Test,
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    // Fork tests so the context classloader carries the full test classpath
    // (needed by InMemoryScalaCompiler's forked dotc).
    Test / fork := true
  )

// sbt binding, mirroring pkl-gradle's kotlinCodeGenerators (spec + cached task + wiring).
lazy val pklSbt = project
  .in(file("pkl-sbt"))
  .enablePlugins(ScriptedPlugin)
  .dependsOn(pkl)
  .settings(
    name := "pkl-sbt",
    sbtPlugin := true,
    // sbt plugins compile against sbt's own API jars; our 3.3.8 scalac cannot read sbt 2.0.11's
    // 3.8.x TASTy (forward-incompatible), so build the plugin with sbt's Scala version.
    // (The pkl project dep stays 3.3.8: newer scalac reads older TASTy fine.)
    scalaVersion := "3.8.4"
  )

// Mill binding, mirroring pkl-gradle's kotlinCodeGenerators (trait + wiring).
lazy val millPkl = project
  .in(file("mill-pkl"))
  .dependsOn(pkl)
  .settings(
    name := "mill-pkl",
    // mill-libs 1.3.1 itself is built with Scala 3.9.0: older scalac cannot read its TASTy
    // (forward-incompatible), so build this module with 3.9.0. (The pkl project dep stays 3.3.8:
    // newer scalac reads older TASTy fine.)
    scalaVersion := "3.9.0",
    // mill-libs' pom carries a `scala-library:3.9.0` newer than our toolchain and a stale
    // `scala-reflect:2.13.18` (which sbt rewrites to the phantom `scala-reflect:3.9.0`): exclude
    // both and compile against the toolchain's own 2.13.x. Mill/$ivy consumers resolve the pom
    // literally (no sbt rewrite) and are unaffected.
    // mill also drags in coursier built for 2.13: readable bytecode scalac needs for mill
    // signatures, so keep it — but drop its _2.13 stdlib dupes that clash with our _3 ones.
    libraryDependencies += ("com.lihaoyi" %% "mill-libs-scalalib" % "1.3.1")
      .exclude("org.scala-lang", "scala-reflect")
      .exclude("org.scala-lang", "scala-library")
      .exclude("org.scala-lang.modules", "scala-xml_2.13")
      .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
  )
