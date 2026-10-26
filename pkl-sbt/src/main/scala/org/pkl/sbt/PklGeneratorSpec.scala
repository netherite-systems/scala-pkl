package org.pkl.sbt

import java.io.File

/** One Pkl-to-Scala generator invocation, mirroring
  * `CodeGenSpec`/`KotlinCodeGenSpec` in `pkl-gradle` (only the options that
  * apply to the Scala generator).
  */
final case class PklGeneratorSpec(
    /** Pkl source modules (tracked task inputs; regenerated when their contents
      * change).
      */
    sourceModules: Seq[File],
    /** Characters used to indent generated code. */
    indent: String = "  ",
    /** Whether to preserve Pkl doc comments as Scaladoc comments. */
    generateScaladoc: Boolean = false,
    /** Whether generated classes extend `Serializable`. */
    implementSerializable: Boolean = false,
    /** Whether to add a `@Generated` annotation to generated types. */
    addGeneratedAnnotation: Boolean = false,
    /** Maps Pkl module name prefixes to replacements. */
    renames: Map[String, String] = Map.empty
)
