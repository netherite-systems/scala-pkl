// Ported from https://github.com/apple/pkl (Apache-2.0).
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
