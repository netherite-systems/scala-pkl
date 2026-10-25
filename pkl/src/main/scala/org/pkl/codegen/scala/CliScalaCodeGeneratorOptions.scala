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

import java.net.URI
import java.nio.file.Path

/** Configuration for [[CliScalaCodeGenerator]].
  *
  * Deliberately independent of `pkl-commons-cli`: the sbt/mill plugin seam only
  * needs an output directory plus generator options, and this keeps the CLI
  * runnable without Kotlin runtime deps.
  */
final case class CliScalaCodeGeneratorOptions(
    /** Source modules to generate from (paths or URIs). */
    modules: List[URI],
    /** Directory where generated source code is placed. */
    outputDir: Path,
    /** Characters used to indent generated code. */
    indent: String = "  ",
    /** Whether to preserve Pkl doc comments as Scaladoc comments. */
    generateScaladoc: Boolean = false,
    /** Whether generated classes extend `Serializable`. */
    implementSerializable: Boolean = false,
    /** Whether to add a `@Generated` annotation to generated types. */
    addGeneratedAnnotation: Boolean = false,
    /** Rename mapping for class/package names (`--rename old=new`, repeatable).
      */
    renames: Map[String, String] = Map.empty
):
  def toGeneratorOptions: ScalaCodeGeneratorOptions = ScalaCodeGeneratorOptions(
    indent,
    generateScaladoc,
    implementSerializable,
    addGeneratedAnnotation,
    renames
  )
