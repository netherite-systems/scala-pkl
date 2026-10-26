// Ported from https://github.com/apple/pkl (Apache-2.0).
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
