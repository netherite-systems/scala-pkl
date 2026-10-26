package org.pkl.sbt

import org.pkl.codegen.scala.ScalaCodeGenerator
import org.pkl.codegen.scala.ScalaCodeGeneratorOptions
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource
import sbt.util.Logger

import java.io.File
import java.nio.file.Files

/** Runs generator specs and writes the `output` map under managed directories.
  *
  * Shared worker used by `PklPlugin`; deliberately parallel to
  * `CliScalaCodeGenerator` but target-aware (sbt manages `scala/` sources and
  * `resources/` separately).
  */
private[sbt] object PklGenerate {

  /** Runs every spec, returns `(relativePath, absoluteFile)` pairs
    * (`scala/...`, `resources/...`).
    */
  def run(
      specs: Seq[PklGeneratorSpec],
      scalaDir: File,
      resourcesDir: File,
      log: Logger
  ): Seq[(String, File)] = {
    val evaluator = Evaluator.preconfigured()
    try {
      specs.flatMap { spec =>
        val options = ScalaCodeGeneratorOptions(
          spec.indent,
          spec.generateScaladoc,
          spec.implementSerializable,
          spec.addGeneratedAnnotation,
          spec.renames
        )
        spec.sourceModules.flatMap { module =>
          log.info(s"Generating Scala sources from Pkl module `$module`.")
          val schema =
            evaluator.evaluateSchema(ModuleSource.path(module.toPath))
          ScalaCodeGenerator(schema, options).output.map {
            (relPath, contents) =>
              // `output` keys are `scala/...` and `resources/...`; sbt manages each root
              // separately, so strip the first segment before resolving under the managed dir.
              val (baseDir, stripped) = relPath.span(_ != '/') match {
                case ("scala", rest)     => (scalaDir, rest.stripPrefix("/"))
                case ("resources", rest) =>
                  (resourcesDir, rest.stripPrefix("/"))
                case _ => (scalaDir, relPath)
              }
              val out = new File(baseDir, stripped)
              Files.createDirectories(out.getParentFile.toPath)
              Files.writeString(out.toPath, contents)
              (relPath, out)
          }
        }
      }
    } finally evaluator.close()
  }
}
