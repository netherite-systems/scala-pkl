/*
 * Copyright © 2026 the scala-pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.codegen.scala

import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource

import java.nio.file.Files

/** Thrown for CLI-level failures (I/O, unsupported schemas). */
final class CliException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

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
              throw CliException(e.getMessage, e)
        for (fileName, contents) <- files do
          val outputFile = options.outputDir.resolve(fileName)
          try
            Files.createDirectories(outputFile.getParent)
            Files.writeString(outputFile, contents)
          catch
            case e: java.io.IOException =>
              throw CliException(
                s"I/O error writing file `$outputFile`.\nCause: ${e.getMessage}",
                e
              )
    finally evaluator.close()
