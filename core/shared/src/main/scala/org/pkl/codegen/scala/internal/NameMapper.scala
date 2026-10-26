// Ported from https://github.com/apple/pkl (Apache-2.0).
package org.pkl.codegen.scala.internal

/** Translates Pkl module names to `(package, class)` names in generated code.
  *
  * Port of `org.pkl.commons.NameMapper` (pkl-commons is not a dependency of
  * this project, so the ~25 lines are duplicated here under the same Apache 2.0
  * license). Semantics are identical: the longest matching source prefix wins;
  * replacements are literal (dots matter); unless the rename explicitly renames
  * the class, the class name is capitalized.
  */
final class NameMapper(mapping: Map[String, String]):
  private val sortedMapping: List[(String, String)] =
    mapping.toList.sortBy(-_._1.length)

  private def doMap(sourceName: String): (String, Boolean) =
    sortedMapping
      .collectFirst {
        case (sourcePrefix, targetPrefix)
            if sourceName.startsWith(sourcePrefix) =>
          val rest = sourceName.substring(sourcePrefix.length)
          val mapped = targetPrefix + rest
          val wasClassRenamed =
            !targetPrefix.endsWith(".") &&
              (sourcePrefix.length - 1) >= sourceName.lastIndexOf('.')
          (mapped, wasClassRenamed)
      }
      .getOrElse((sourceName, false))

  /** Maps a Pkl module name to `(packageName, className)`. */
  def map(sourceName: String): (String, String) =
    val (mappedName, wasClassRenamed) = doMap(sourceName)
    val packageName =
      if mappedName.contains('.') then
        mappedName.substring(0, mappedName.lastIndexOf('.'))
      else ""
    val mappedClassName =
      if mappedName.contains('.') then
        mappedName.substring(mappedName.lastIndexOf('.') + 1)
      else mappedName
    val className = (wasClassRenamed, mappedClassName) match
      case (true, renamed) => renamed
      case (false, "")     => ""
      case (false, name)   => name.capitalize
    (packageName, className)
