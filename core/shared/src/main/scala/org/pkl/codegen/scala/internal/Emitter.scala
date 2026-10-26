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

import scala.collection.mutable

/** Minimal plain-string code emitter (no AST dependency, per project decision).
  *
  * Collects a body plus the imports it needs; [[result]] renders `package` +
  * sorted imports + body. Cross-module type references go through [[reference]]
  * so name clashes degrade to fully qualified names instead of broken imports.
  */
final class Emitter(val indent: String):
  private val body: StringBuilder = new StringBuilder
  private var level: Int = 0
  private val importSet: mutable.LinkedHashSet[String] =
    mutable.LinkedHashSet.empty
  private val simpleToPackage: mutable.LinkedHashMap[String, String] =
    mutable.LinkedHashMap.empty

  /** Records `fqn` as a needed import. */
  def addImport(fqn: String): Unit =
    importSet += fqn

  /** Renders a reference to type `inner` in `pkg`, nested in `outer` (possibly
    * empty).
    *
    * Nested references always render qualified (`Outer.Inner`). First use of a
    * simple name wins the import (`pkg.Outer`, or `pkg.inner` when top-level);
    * later clashes with a different package render as fully qualified names.
    * Same-package imports are filtered in [[result]].
    */
  def reference(pkg: String, outer: String, inner: String): String =
    val full = if outer.isEmpty then inner else s"$outer.$inner"
    val importee = if outer.isEmpty then s"$pkg.$inner" else s"$pkg.$outer"
    if pkg.isEmpty then full
    else
      simpleToPackage.get(inner) match
        case None =>
          simpleToPackage(inner) = pkg
          addImport(importee)
          full
        case Some(existing) if existing == pkg => full
        case _                                 => s"$pkg.$full"

  def emit(line: String = ""): Unit =
    if line.isEmpty then body.append("\n")
    else
      var i = 0
      while i < level do
        body.append(indent)
        i += 1
      body.append(line).append("\n")

  def indented(fn: => Unit): Unit =
    level += 1
    try fn
    finally level -= 1

  /** Renders `package ...`, the import block, then the body. */
  def result(packageName: String): String =
    val out = new StringBuilder
    if packageName.nonEmpty then out.append(s"package $packageName\n\n")
    val relevant =
      importSet.toList
        .filterNot(fqn =>
          packageName.nonEmpty && fqn.startsWith(packageName + ".")
        )
        .sorted
    if relevant.nonEmpty then
      relevant.foreach(i => out.append(s"import $i\n"))
      out.append("\n")
    out.append(body.result())
    out.result()

object Emitter:
  /** Scala 3 keywords (hard + soft). Anything else that is not a plain
    * identifier is backticked.
    */
  private val keywords: Set[String] = Set(
    "abstract",
    "as",
    "case",
    "catch",
    "class",
    "def",
    "derives",
    "do",
    "else",
    "end",
    "enum",
    "extension",
    "false",
    "final",
    "finally",
    "for",
    "forSome",
    "given",
    "if",
    "implicit",
    "import",
    "in",
    "infix",
    "inline",
    "into",
    "lazy",
    "macro",
    "match",
    "new",
    "null",
    "opaque",
    "open",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "throws",
    "trait",
    "transparent",
    "true",
    "try",
    "type",
    "using",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )

  private val identPattern = "^[a-zA-Z_][a-zA-Z0-9_]*$".r

  /** Escapes `name` for use as a Scala identifier. */
  def escape(name: String): String =
    if keywords.contains(name) || identPattern.findFirstIn(name).isEmpty then
      s"`$name`"
    else name

  /** Minimal Scaladoc rendering (mirrors Kotlin's `renderAsKdoc`). */
  def scaladoc(comment: String): List[String] =
    val safe = comment.replace("*/", "*&#47;")
    val lines = safe.linesIterator.toList
    lines match
      case Nil           => Nil
      case single :: Nil => List(s"/** $single */")
      case _ => List("/**") ++ lines.map(l => s" * $l") ++ List(" */")
