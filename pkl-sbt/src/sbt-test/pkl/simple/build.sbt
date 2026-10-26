import org.pkl.sbt.PklGeneratorSpec

ThisBuild / scalaVersion := "3.3.8"

lazy val app = project
  .in(file("."))
  .enablePlugins(PklPlugin)
  .settings(
    name := "scripted-simple",
    pklGenerators += PklGeneratorSpec(Seq(file("pkl/birds.pkl")))
  )
