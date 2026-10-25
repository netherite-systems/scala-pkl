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
    val className =
      if wasClassRenamed then mappedClassName
      else if mappedClassName.nonEmpty then
        mappedClassName.substring(0, 1).toUpperCase + mappedClassName.substring(
          1
        )
      else mappedClassName
    (packageName, className)
