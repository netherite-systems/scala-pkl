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

/** Options for [[ScalaCodeGenerator]].
  *
  * Mirrors `KotlinCodeGeneratorOptions` from `pkl-codegen-kotlin`, minus Spring
  * Boot support (there is no Scala Spring equivalent) and KDoc (replaced by
  * Scaladoc).
  *
  * Pure data: lives in the cross-built core so sbt/mill plugins on any platform
  * can construct it.
  */
final case class ScalaCodeGeneratorOptions(
    /** The characters used to indent generated Scala code. */
    indent: String = "  ",
    /** Whether to preserve Pkl doc comments as Scaladoc comments. */
    generateScaladoc: Boolean = false,
    /** Whether generated classes extend `Serializable`. */
    implementSerializable: Boolean = false,
    /** Whether to add a `@Generated` annotation to generated types. */
    addGeneratedAnnotation: Boolean = false,
    /** Maps Pkl module name prefixes to replacements (see
      * [[internal.NameMapper]]).
      */
    renames: Map[String, String] = Map.empty
)
