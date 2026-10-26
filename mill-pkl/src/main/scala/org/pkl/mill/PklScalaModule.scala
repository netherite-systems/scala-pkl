package org.pkl.mill

import mill._
import mill.scalalib._
import org.pkl.codegen.scala.ScalaCodeGenerator
import org.pkl.codegen.scala.ScalaCodeGeneratorOptions
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource

import java.nio.file.Files

/** Mill binding for `pkl-codegen-scala`, mirroring `pkl-gradle`'s
  * `kotlinCodeGenerators`.
  *
  * Mix into a `ScalaModule` and declare `.pkl` sources; generation runs
  * in-process (same JVM, no CLI fork — like `KotlinCodeGenTask`) and feeds
  * mill's `generatedSources`/`resources`, so `compile` picks up generated code
  * with no further wiring. Mill tasks are content-hashed, so regeneration (and
  * the Gradle `GatherImports` equivalent bookkeeping) comes for free.
  *
  * {{{
  * object app extends ScalaModule with PklScalaModule {
  *   def scalaVersion = "3.3.8"
  *   override def pklSources: T[Seq[PathRef]] = Task.Sources(millSourcePath / "pkl")
  * }
  * }}}
  */
trait PklScalaModule extends ScalaModule {

  /** Pkl source modules (tracked task inputs). Defaults to a `pkl/` directory
    * next to `build.sc`.
    */
  def pklSources: T[Seq[PathRef]] = Task.Sources(moduleDir / "pkl")

  /** Characters used to indent generated code. */
  def pklIndent: T[String] = Task { "  " }

  /** Whether to preserve Pkl doc comments as Scaladoc comments. */
  def pklGenerateScaladoc: T[Boolean] = Task { false }

  /** Whether generated classes extend `Serializable`. */
  def pklImplementSerializable: T[Boolean] = Task { false }

  /** Whether to add a `@Generated` annotation to generated types. */
  def pklAddGeneratedAnnotation: T[Boolean] = Task { false }

  /** Maps Pkl module name prefixes to replacements. */
  def pklRenames: T[Map[String, String]] = Task { Map.empty[String, String] }

  /** Runs generation once; `generatedSources`/`resources` split the pair
    * (single evaluation).
    */
  def pklGenerate: T[(Seq[PathRef], Seq[PathRef])] = Task {
    val dest = Task.dest
    val scalaDir = dest / "scala"
    val resDir = dest / "resources"
    val options = ScalaCodeGeneratorOptions(
      pklIndent(),
      pklGenerateScaladoc(),
      pklImplementSerializable(),
      pklAddGeneratedAnnotation(),
      pklRenames()
    )
    val evaluator = Evaluator.preconfigured()
    try {
      val pairs = pklSources().flatMap { ref =>
        Task.log.info(
          s"Generating Scala sources from Pkl module `${ref.path}`."
        )
        val schema =
          evaluator.evaluateSchema(ModuleSource.uri(ref.path.toIO.toURI))
        ScalaCodeGenerator(schema, options).output.map { (relPath, contents) =>
          // `output` keys are `scala/...` and `resources/...`; resolve each under its dir.
          val base = if (relPath.startsWith("scala/")) scalaDir else resDir
          val out = relPath.split("/").drop(1).foldLeft(base)(_ / _)
          Files.createDirectories(out.toNIO.getParent)
          Files.writeString(out.toNIO, contents)
          (relPath, PathRef(out))
        }
      }
      (
        pairs.collect { case (rel, ref) if rel.startsWith("scala/") => ref },
        pairs.collect { case (rel, ref) if rel.startsWith("resources/") => ref }
      )
    } finally evaluator.close()
  }

  override def generatedSources: T[Seq[PathRef]] = Task { pklGenerate()._1 }

  override def resources: T[Seq[PathRef]] = Task {
    super.resources() ++ pklGenerate()._2
  }
}
