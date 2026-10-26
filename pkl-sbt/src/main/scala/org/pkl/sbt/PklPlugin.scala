package org.pkl.sbt

import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import sbt.util.FileFunction
import sbt.util.FileInfo

import java.io.File

/** sbt binding for `pkl-codegen-scala`, mirroring `pkl-gradle`'s
  * `kotlinCodeGenerators`:
  *
  * {{{
  * lazy val app = project.enablePlugins(PklPlugin).settings(
  *   pklGenerators += PklGeneratorSpec(Seq(file("src/main/pkl/birds.pkl")))
  * )
  * }}}
  *
  * Generation runs in-process (same JVM, no CLI fork — like
  * `KotlinCodeGenTask`), is cached on spec inputs via `FileFunction`, and feeds
  * sbt's `sourceGenerators`/`resourceGenerators`, so `compile` picks up
  * generated code with no further wiring.
  *
  * sbt 2 caches every task result, so file outputs travel as
  * `HashedVirtualFileRef` (see
  * https://www.scala-sbt.org/2.x/docs/en/concepts/caching.html); the thin
  * `sourceGenerators` adapters below convert back to `File`.
  */
object PklPlugin extends AutoPlugin {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  object autoImport {
    val pklGenerators: SettingKey[Seq[PklGeneratorSpec]] =
      settingKey[Seq[PklGeneratorSpec]](
        "Pkl modules to generate Scala 3 sources from."
      )

    val pklGenerate: TaskKey[Seq[String]] =
      taskKey[Seq[String]](
        "Generate Scala 3 sources from Pkl modules; returns generated relative paths."
      )
  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    pklGenerators := Nil,
    pklGenerate := generate.value,
    Compile / sourceGenerators += Def.task {
      val root = (Compile / sourceManaged).value / "pkl"
      pklGenerate.value.collect {
        case rel if rel.startsWith("scala/") =>
          new File(root, rel.stripPrefix("scala/"))
      }
    }.taskValue,
    Compile / resourceGenerators += Def.task {
      val root = (Compile / resourceManaged).value / "pkl"
      pklGenerate.value.collect {
        case rel if rel.startsWith("resources/") =>
          new File(root, rel.stripPrefix("resources/"))
      }
    }.taskValue
  )

  private def generate: Def.Initialize[Task[Seq[String]]] = Def.task {
    val specs = pklGenerators.value
    val scalaDir = (Compile / sourceManaged).value / "pkl"
    val resourcesDir = (Compile / resourceManaged).value / "pkl"
    val log = streams.value.log
    // Cache on input contents; options are part of the cache key via a hashed subdir so
    // option-only changes still regenerate (mirrors Gradle's up-to-date checks).
    val optionsHash = specs
      .map(s =>
        (
          s.indent,
          s.generateScaladoc,
          s.implementSerializable,
          s.addGeneratedAnnotation,
          s.renames
        ).hashCode()
      )
      .hashCode()
      .toHexString
    val inputs: Set[File] = specs.flatMap(_.sourceModules).toSet
    val cached =
      FileFunction.cached(
        streams.value.cacheDirectory / s"pkl-generate-$optionsHash",
        FileInfo.hash
      ) { (_: Set[File]) =>
        PklGenerate.run(specs, scalaDir, resourcesDir, log).map(_._2).toSet
      }
    // Cache hits skip rewriting but files remain on disk; return rel paths (Strings are
    // trivially cache-serializable; the adapters above resolve them under managed roots).
    val scalaRoot = scalaDir.toPath
    val resourcesRoot = resourcesDir.toPath
    cached(inputs).toSeq.map { file =>
      val path = file.toPath
      if (path.startsWith(scalaRoot))
        "scala/" + scalaRoot.relativize(path).toString
      else "resources/" + resourcesRoot.relativize(path).toString
    }
  }
}
