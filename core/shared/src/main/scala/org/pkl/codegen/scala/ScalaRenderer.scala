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

import org.pkl.codegen.scala.internal.Emitter
import org.pkl.codegen.scala.ir._

/** Renders an [[ir.IrModule]] as Scala 3 source code.
  *
  * Pure string building (see [[internal.Emitter]]): no pkl-core dependency, so
  * this class cross-builds for JVM, Scala.js and Scala Native. All schema
  * interpretation (enum detection, renames, hidden filtering, copy analysis)
  * happens up front in the JVM-only `PklSchemaAdapter`.
  */
final class ScalaRenderer(options: ScalaCodeGeneratorOptions):

  def renderModule(module: IrModule): String =
    val emitter = new Emitter(options.indent)
    val escWrapper = Emitter.escape(module.moduleTypeName)
    // Nested classes live in the wrapper so they stay statically accessible (nesting them inside
    // a case class would make them path-dependent inner classes). Hence a module with properties
    // renders as a class plus an `object Mod` companion holding the nested classes.
    module.moduleClass match
      case Some(cls) =>
        emitClass(emitter, cls)
        if module.classes.nonEmpty then
          emitter.emit()
          emitDoc(emitter, module.moduleDoc)
          emitter.emit(s"object $escWrapper:")
          emitter.indented:
            emitNested(emitter, module.classes)
      case None =>
        emitDoc(emitter, module.moduleDoc)
        if module.classes.isEmpty then emitter.emit(s"object $escWrapper")
        else
          emitter.emit(s"object $escWrapper:")
          emitter.indented:
            emitNested(emitter, module.classes)

    for decl <- module.decls do
      emitter.emit()
      decl match
        case IrModuleDecl.Enum(e)  => emitEnum(emitter, e)
        case IrModuleDecl.Alias(a) => emitTypeAlias(emitter, a)

    emitter.result(module.packageName)

  private def emitNested(emitter: Emitter, classes: List[IrClass]): Unit =
    var first = true
    for cls <- classes do
      if first then first = false else emitter.emit()
      emitClass(emitter, cls)

  // --------------------------------------------------------------------------
  // classes
  // --------------------------------------------------------------------------

  private def emitClass(emitter: Emitter, cls: IrClass): Unit =
    val escName = Emitter.escape(cls.name)
    emitDoc(emitter, cls.doc)
    cls.deprecated.foreach(msg => emitter.emit(deprecationLine(msg)))
    if cls.generated then emitGenerated(emitter)

    val ownNames = cls.ownProps.map(_.name).toSet
    val superNames = cls.superProps.map(_.name).toSet
    val allProps = cls.allProps
    val typeParams = typeParamsString(cls.typeParams)
    val superCall: Option[String] = cls.superType.map { base =>
      val rendered = renderType(base, emitter)
      val args = cls.superArgNames.map(Emitter.escape).mkString(", ")
      s"$rendered($args)"
    }

    cls.kind match
      case IrClassKind.Case =>
        emitCaseClass(emitter, escName, typeParams, allProps, cls.serializable)
      case IrClassKind.Abstract =>
        val header =
          s"abstract class $escName$typeParams(${ctorParams(emitter, allProps, ownNames, superNames, isCase = false)})" +
            extendsClause(superCall, cls.serializable)
        emitter.emit(header)
      case IrClassKind.Open =>
        val header =
          s"open class $escName$typeParams(${ctorParams(emitter, allProps, ownNames, superNames, isCase = false)})" +
            extendsClause(superCall, cls.serializable)
        emitter.emit(header + ":")
        emitter.indented:
          cls.copy.foreach(emitCopyMethods(emitter, _, escName, allProps))
          emitEquals(emitter, escName, allProps)
          emitHashCode(emitter, allProps)
          emitToString(emitter, escName, allProps)

  private def emitCaseClass(
      emitter: Emitter,
      escName: String,
      typeParams: String,
      allProps: List[IrProperty],
      serializable: Boolean
  ): Unit =
    val hasRegexOrBytes = allProps.exists(p => isRegex(p.tpe) || isBytes(p.tpe))
    val hasBytes = allProps.exists(p => isBytes(p.tpe))
    val header =
      s"case class $escName$typeParams(${ctorParams(emitter, allProps, Set.empty, Set.empty, isCase = true)})" +
        extendsClause(None, serializable)
    if !hasRegexOrBytes && !hasBytes then emitter.emit(header)
    else
      emitter.emit(header + ":")
      emitter.indented:
        if hasRegexOrBytes then
          emitEquals(emitter, escName, allProps)
          emitHashCode(emitter, allProps)
        if hasBytes then emitToString(emitter, escName, allProps)

  private def extendsClause(
      superCall: Option[String],
      serializable: Boolean
  ): String =
    (superCall, serializable) match
      case (None, false)    => ""
      case (Some(s), false) => s" extends $s"
      case (None, true)     => " extends Serializable"
      case (Some(s), true)  => s" extends $s with Serializable"

  private def ctorParams(
      emitter: Emitter,
      allProps: List[IrProperty],
      ownNames: Set[String],
      superNames: Set[String],
      isCase: Boolean
  ): String =
    allProps
      .map { prop =>
        val tpe = renderType(prop.tpe, emitter)
        val deprecation =
          prop.deprecated.map(msg => deprecationLine(msg) + " ").getOrElse("")
        val doc =
          if options.generateScaladoc then
            prop.doc
              .map(c => Emitter.scaladoc(c).mkString(" ") + " ")
              .getOrElse("")
          else ""
        val esc = Emitter.escape(prop.name)
        if isCase then s"$doc$deprecation$esc: $tpe"
        else if ownNames.contains(prop.name) then
          val overrideMod =
            if superNames.contains(prop.name) then "override " else ""
          s"$doc$deprecation${overrideMod}val $esc: $tpe"
        else s"$esc: $tpe"
      }
      .mkString(", ")

  private def typeParamsString(params: List[IrTypeParam]): String =
    if params.isEmpty then ""
    else
      params
        .map { tp =>
          val variance = tp.variance match
            case IrVariance.Covariant     => "+"
            case IrVariance.Contravariant => "-"
            case IrVariance.Invariant     => ""
          s"$variance${Emitter.escape(tp.name)}"
        }
        .mkString("[", ", ", "]")

  // --------------------------------------------------------------------------
  // copy / equals / hashCode / toString
  // --------------------------------------------------------------------------

  private def emitCopyMethods(
      emitter: Emitter,
      copy: IrCopy,
      escName: String,
      allProps: List[IrProperty]
  ): Unit =
    val params = allProps
      .map { prop =>
        val decl =
          s"${Emitter.escape(prop.name)}: ${renderType(prop.tpe, emitter)}"
        if copy.withDefaults then s"$decl = this.${Emitter.escape(prop.name)}"
        else decl
      }
      .mkString(", ")
    val args = allProps.map(p => Emitter.escape(p.name)).mkString(", ")
    if copy.mainOverride then
      emitter.emit(s"override def copy($params): $escName = $escName($args)")
    else emitter.emit(s"def copy($params): $escName = $escName($args)")
    // ancestor-arity overrides (no defaults, own return type)
    for overrideProps <- copy.overrides do
      val sig = overrideProps
        .map { prop =>
          s"${Emitter.escape(prop.name)}: ${renderType(prop.tpe, emitter)}"
        }
        .mkString(", ")
      emitter.emit(s"override def copy($sig): $escName = $escName($args)")

  private def emitEquals(
      emitter: Emitter,
      escName: String,
      allProps: List[IrProperty]
  ): Unit =
    emitter.emit("override def equals(other: Any): Boolean =")
    emitter.indented:
      emitter.emit(
        "if (this.asInstanceOf[AnyRef] eq other.asInstanceOf[AnyRef]) return true"
      )
      emitter.emit("if (this.getClass != other.getClass) return false")
      emitter.emit(s"val o = other.asInstanceOf[$escName]")
      for prop <- allProps do
        val esc = Emitter.escape(prop.name)
        if isBytes(prop.tpe) then
          emitter.emit(
            s"if (!java.util.Arrays.equals(this.$esc, o.$esc)) return false"
          )
        else if isRegex(prop.tpe) then
          emitter.emit(s"if (this.$esc.pattern != o.$esc.pattern) return false")
        else emitter.emit(s"if (this.$esc != o.$esc) return false")
      emitter.emit("true")

  private def emitHashCode(emitter: Emitter, allProps: List[IrProperty]): Unit =
    emitter.emit("override def hashCode(): Int =")
    emitter.indented:
      emitter.emit("var result = 1")
      for prop <- allProps do
        val esc = Emitter.escape(prop.name)
        if isBytes(prop.tpe) then
          emitter.emit(
            s"result = 31 * result + java.util.Arrays.hashCode(this.$esc)"
          )
        else if isRegex(prop.tpe) then
          emitter.emit(
            s"result = 31 * result + java.util.Objects.hashCode(this.$esc.pattern)"
          )
        else
          emitter.emit(
            s"result = 31 * result + java.util.Objects.hashCode(this.$esc)"
          )
      emitter.emit("result")

  private def emitToString(
      emitter: Emitter,
      escName: String,
      allProps: List[IrProperty]
  ): Unit =
    val parts = allProps
      .map { prop =>
        val esc = Emitter.escape(prop.name)
        if isBytes(prop.tpe) then s"$esc=$${java.util.Arrays.toString($esc)}"
        else s"$esc=$$$esc"
      }
      .mkString(", ")
    emitter.emit(
      s"override def toString: String = s\"\"\"$escName($parts)\"\"\""
    )

  private def isRegex(tpe: IrType): Boolean = tpe == IrType.Regex
  private def isBytes(tpe: IrType): Boolean = tpe == IrType.Bytes

  // --------------------------------------------------------------------------
  // enums and type aliases
  // --------------------------------------------------------------------------

  private def emitEnum(emitter: Emitter, e: IrEnum): Unit =
    emitDoc(emitter, e.doc)
    e.deprecated.foreach(msg => emitter.emit(deprecationLine(msg)))
    if e.generated then emitGenerated(emitter)
    val escName = Emitter.escape(e.name)
    emitter.emit(s"enum $escName(val value: String):")
    emitter.indented:
      for c <- e.cases do
        emitter.emit(
          s"case ${c.constName} extends $escName(${quote(c.literal)})"
        )
      emitter.emit("override def toString: String = value")

  private def emitTypeAlias(emitter: Emitter, a: IrTypeAlias): Unit =
    emitDoc(emitter, a.doc)
    a.deprecated.foreach(msg => emitter.emit(deprecationLine(msg)))
    if a.generated then emitGenerated(emitter)
    val escName = Emitter.escape(a.name)
    val params = typeParamsString(a.params)
    val rhs = renderType(a.rhs, emitter)
    emitter.emit(s"type $escName$params = $rhs")

  // --------------------------------------------------------------------------
  // IrType -> Scala type
  // --------------------------------------------------------------------------

  private def renderType(tpe: IrType, emitter: Emitter): String =
    tpe match
      case IrType.Any          => "Any"
      case IrType.Nothing      => "Nothing"
      case IrType.Str          => "String"
      case IrType.Bool         => "Boolean"
      case IrType.Long         => "Long"
      case IrType.Double       => "Double"
      case IrType.Byte         => "Byte"
      case IrType.Short        => "Short"
      case IrType.Int          => "Int"
      case IrType.Duration     => qualified(emitter, "org.pkl.core", "Duration")
      case IrType.DataSize     => qualified(emitter, "org.pkl.core", "DataSize")
      case IrType.DurationUnit =>
        qualified(emitter, "org.pkl.core", "DurationUnit")
      case IrType.DataSizeUnit =>
        qualified(emitter, "org.pkl.core", "DataSizeUnit")
      case IrType.Uri     => qualified(emitter, "java.net", "URI")
      case IrType.Version => qualified(emitter, "org.pkl.core", "Version")
      case IrType.PModule => qualified(emitter, "org.pkl.core", "PModule")
      case IrType.PClass  => qualified(emitter, "org.pkl.core", "PClass")
      case IrType.Bytes   => "Array[Byte]"
      case IrType.Regex   => qualified(emitter, "scala.util.matching", "Regex")
      case IrType.PairType(first, second) =>
        s"(${renderType(first, emitter)}, ${renderType(second, emitter)})"
      case IrType.IterableOf(elem)  => s"Iterable[${renderType(elem, emitter)}]"
      case IrType.ListOf(elem)      => s"List[${renderType(elem, emitter)}]"
      case IrType.SetOf(elem)       => s"Set[${renderType(elem, emitter)}]"
      case IrType.MapOf(key, value) =>
        s"Map[${renderType(key, emitter)}, ${renderType(value, emitter)}]"
      case IrType.OptionOf(base) => s"Option[${renderType(base, emitter)}]"
      case IrType.Ref(pkg, outer, name, args) =>
        val escOuter = if outer.isEmpty then "" else Emitter.escape(outer)
        val base = emitter.reference(pkg, escOuter, Emitter.escape(name))
        if args.isEmpty then base
        else base + args.map(renderType(_, emitter)).mkString("[", ", ", "]")
      case IrType.TypeVar(name) => Emitter.escape(name)

  /** Reference to a type in a well-known package; records the import. */
  private def qualified(emitter: Emitter, pkg: String, simple: String): String =
    emitter.addImport(s"$pkg.$simple")
    simple

  // --------------------------------------------------------------------------
  // misc helpers
  // --------------------------------------------------------------------------

  private def emitGenerated(emitter: Emitter): Unit =
    emitter.addImport("javax.annotation.processing.Generated")
    emitter.emit("""@Generated(Array("pkl-codegen-scala"))""")

  private def emitDoc(emitter: Emitter, docComment: Option[String]): Unit =
    if options.generateScaladoc then
      docComment.foreach(c => Emitter.scaladoc(c).foreach(emitter.emit))

  private def deprecationLine(message: String): String =
    s"@deprecated(${quote(message)}, \"\")"

  private def quote(s: String): String =
    "\"" + s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n") + "\""
