package org.pkl.codegen.scala

import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource

import java.nio.file.Files

/** Thrown for CLI-level failures (I/O, unsupported schemas). */
final class CliException(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

/** API for the Scala code generator CLI; also the reference file-writing loop
  * for sbt/mill plugins.
  *
  * For each module: `evaluateSchema` → `ScalaCodeGenerator(...).output` → write
  * every entry under `outputDir`. Uses a single preconfigured evaluator per
  * run.
  */
final class CliScalaCodeGenerator(options: CliScalaCodeGeneratorOptions):
  def run(): Unit =
    val evaluator = Evaluator.preconfigured()
    try
      for moduleUri <- options.modules do
        val schema = evaluator.evaluateSchema(ModuleSource.uri(moduleUri))
        val generator = ScalaCodeGenerator(schema, options.toGeneratorOptions)
        val files =
          try generator.output
          catch
            case e: ScalaCodeGeneratorException =>
              throw CliException(e.getMessage, Some(e))
        for (fileName, contents) <- files do
          val outputFile = options.outputDir.resolve(fileName)
          try
            Files.createDirectories(outputFile.getParent)
            Files.writeString(outputFile, contents)
          catch
            case e: java.io.IOException =>
              throw CliException(
                s"I/O error writing file `$outputFile`.\nCause: ${e.getMessage}",
                Some(e)
              )
    finally evaluator.close()
