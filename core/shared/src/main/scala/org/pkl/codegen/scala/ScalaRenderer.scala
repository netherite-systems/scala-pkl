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
    val escapedWrapper = Emitter.escape(module.moduleTypeName)
    module.moduleClass match
      case Some(cls) =>
        emitClass(emitter, cls)
        if module.classes.nonEmpty then
          emitter.emit()
          emitDoc(emitter, module.moduleDoc)
          emitter.emit(s"object $escapedWrapper:")
          emitter.indented:
            emitNested(emitter, module.classes)
      case None =>
        emitDoc(emitter, module.moduleDoc)
        if module.classes.isEmpty then emitter.emit(s"object $escapedWrapper")
        else
          emitter.emit(s"object $escapedWrapper:")
          emitter.indented:
            emitNested(emitter, module.classes)

    for decl <- module.decls do
      emitter.emit()
      decl match
        case IrModuleDecl.Enum(enumDecl) => emitEnum(emitter, enumDecl)
        case IrModuleDecl.Alias(alias)   => emitTypeAlias(emitter, alias)

    emitter.result(module.packageName)

  private def emitNested(emitter: Emitter, classes: List[IrClass]): Unit =
    classes.zipWithIndex.foreach { (cls, index) =>
      if index > 0 then emitter.emit()
      emitClass(emitter, cls)
    }

  private def emitClass(emitter: Emitter, cls: IrClass): Unit =
    val escapedName = Emitter.escape(cls.name)
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
        emitCaseClass(
          emitter,
          escapedName,
          typeParams,
          allProps,
          cls.serializable
        )
      case IrClassKind.Abstract =>
        val header =
          s"abstract class $escapedName$typeParams(${ctorParams(emitter, allProps, ownNames, superNames, isCase = false)})" +
            extendsClause(superCall, cls.serializable)
        emitter.emit(header)
      case IrClassKind.Open =>
        val header =
          s"open class $escapedName$typeParams(${ctorParams(emitter, allProps, ownNames, superNames, isCase = false)})" +
            extendsClause(superCall, cls.serializable)
        emitter.emit(header + ":")
        emitter.indented:
          cls.copy.foreach(emitCopyMethods(emitter, _, escapedName, allProps))
          emitEquals(emitter, escapedName, allProps)
          emitHashCode(emitter, allProps)
          emitToString(emitter, escapedName, allProps)

  private def emitCaseClass(
      emitter: Emitter,
      escapedName: String,
      typeParams: String,
      allProps: List[IrProperty],
      serializable: Boolean
  ): Unit =
    val hasRegexOrBytes =
      allProps.exists(prop => isRegex(prop.tpe) || isBytes(prop.tpe))
    val hasBytes = allProps.exists(prop => isBytes(prop.tpe))
    val header =
      s"case class $escapedName$typeParams(${ctorParams(emitter, allProps, Set.empty, Set.empty, isCase = true)})" +
        extendsClause(None, serializable)
    if !hasRegexOrBytes && !hasBytes then emitter.emit(header)
    else
      emitter.emit(header + ":")
      emitter.indented:
        if hasRegexOrBytes then
          emitEquals(emitter, escapedName, allProps)
          emitHashCode(emitter, allProps)
        if hasBytes then emitToString(emitter, escapedName, allProps)

  private def extendsClause(
      superCall: Option[String],
      serializable: Boolean
  ): String =
    (superCall, serializable) match
      case (None, false)       => ""
      case (Some(call), false) => s" extends $call"
      case (None, true)        => " extends Serializable"
      case (Some(call), true)  => s" extends $call with Serializable"

  private def ctorParams(
      emitter: Emitter,
      allProps: List[IrProperty],
      ownNames: Set[String],
      superNames: Set[String],
      isCase: Boolean
  ): String =
    allProps
      .map { prop =>
        val renderedType = renderType(prop.tpe, emitter)
        val deprecation =
          prop.deprecated.map(msg => deprecationLine(msg) + " ").getOrElse("")
        val doc =
          if options.generateScaladoc then
            prop.doc
              .map(comment => Emitter.scaladoc(comment).mkString(" ") + " ")
              .getOrElse("")
          else ""
        val escaped = Emitter.escape(prop.name)
        if isCase then s"$doc$deprecation$escaped: $renderedType"
        else if ownNames.contains(prop.name) then
          val overrideMod =
            if superNames.contains(prop.name) then "override " else ""
          s"$doc$deprecation${overrideMod}val $escaped: $renderedType"
        else s"$escaped: $renderedType"
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

  private def emitCopyMethods(
      emitter: Emitter,
      copy: IrCopy,
      escapedName: String,
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
    val args = allProps.map(prop => Emitter.escape(prop.name)).mkString(", ")
    if copy.mainOverride then
      emitter.emit(
        s"override def copy($params): $escapedName = $escapedName($args)"
      )
    else emitter.emit(s"def copy($params): $escapedName = $escapedName($args)")
    for overrideProps <- copy.overrides do
      val sig = overrideProps
        .map { prop =>
          s"${Emitter.escape(prop.name)}: ${renderType(prop.tpe, emitter)}"
        }
        .mkString(", ")
      emitter.emit(
        s"override def copy($sig): $escapedName = $escapedName($args)"
      )

  private def emitEquals(
      emitter: Emitter,
      escapedName: String,
      allProps: List[IrProperty]
  ): Unit =
    emitter.emit("override def equals(other: Any): Boolean =")
    emitter.indented:
      emitter.emit(
        "if (this.asInstanceOf[AnyRef] eq other.asInstanceOf[AnyRef]) return true"
      )
      emitter.emit("if (this.getClass != other.getClass) return false")
      emitter.emit(s"val o = other.asInstanceOf[$escapedName]")
      for prop <- allProps do
        val escaped = Emitter.escape(prop.name)
        if isBytes(prop.tpe) then
          emitter.emit(
            s"if (!java.util.Arrays.equals(this.$escaped, o.$escaped)) return false"
          )
        else if isRegex(prop.tpe) then
          emitter.emit(
            s"if (this.$escaped.pattern != o.$escaped.pattern) return false"
          )
        else emitter.emit(s"if (this.$escaped != o.$escaped) return false")
      emitter.emit("true")

  private def emitHashCode(emitter: Emitter, allProps: List[IrProperty]): Unit =
    emitter.emit("override def hashCode(): Int =")
    emitter.indented:
      emitter.emit("var result = 1")
      for prop <- allProps do
        val escaped = Emitter.escape(prop.name)
        if isBytes(prop.tpe) then
          emitter.emit(
            s"result = 31 * result + java.util.Arrays.hashCode(this.$escaped)"
          )
        else if isRegex(prop.tpe) then
          emitter.emit(
            s"result = 31 * result + java.util.Objects.hashCode(this.$escaped.pattern)"
          )
        else
          emitter.emit(
            s"result = 31 * result + java.util.Objects.hashCode(this.$escaped)"
          )
      emitter.emit("result")

  private def emitToString(
      emitter: Emitter,
      escapedName: String,
      allProps: List[IrProperty]
  ): Unit =
    val parts = allProps
      .map { prop =>
        val escaped = Emitter.escape(prop.name)
        if isBytes(prop.tpe) then
          s"$escaped=$${java.util.Arrays.toString($escaped)}"
        else s"$escaped=$$$escaped"
      }
      .mkString(", ")
    emitter.emit(
      s"override def toString: String = s\"\"\"$escapedName($parts)\"\"\""
    )

  private def isRegex(tpe: IrType): Boolean = tpe == IrType.Regex
  private def isBytes(tpe: IrType): Boolean = tpe == IrType.Bytes

  private def emitEnum(emitter: Emitter, enumDecl: IrEnum): Unit =
    emitDoc(emitter, enumDecl.doc)
    enumDecl.deprecated.foreach(msg => emitter.emit(deprecationLine(msg)))
    if enumDecl.generated then emitGenerated(emitter)
    val escapedName = Emitter.escape(enumDecl.name)
    emitter.emit(s"enum $escapedName(val value: String):")
    emitter.indented:
      for enumCase <- enumDecl.cases do
        emitter.emit(
          s"case ${enumCase.constName} extends $escapedName(${quote(enumCase.literal)})"
        )
      emitter.emit("override def toString: String = value")

  private def emitTypeAlias(emitter: Emitter, alias: IrTypeAlias): Unit =
    emitDoc(emitter, alias.doc)
    alias.deprecated.foreach(msg => emitter.emit(deprecationLine(msg)))
    if alias.generated then emitGenerated(emitter)
    val escapedName = Emitter.escape(alias.name)
    val params = typeParamsString(alias.params)
    val rhs = renderType(alias.rhs, emitter)
    emitter.emit(s"type $escapedName$params = $rhs")

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
        val escapedOuter = if outer.isEmpty then "" else Emitter.escape(outer)
        val base = emitter.reference(pkg, escapedOuter, Emitter.escape(name))
        if args.isEmpty then base
        else base + args.map(renderType(_, emitter)).mkString("[", ", ", "]")
      case IrType.TypeVar(name) => Emitter.escape(name)

  private def qualified(emitter: Emitter, pkg: String, simple: String): String =
    emitter.addImport(s"$pkg.$simple")
    simple

  private def emitGenerated(emitter: Emitter): Unit =
    emitter.addImport("javax.annotation.processing.Generated")
    emitter.emit("""@Generated(Array("pkl-codegen-scala"))""")

  private def emitDoc(emitter: Emitter, docComment: Option[String]): Unit =
    if options.generateScaladoc then
      docComment.foreach(comment =>
        Emitter.scaladoc(comment).foreach(emitter.emit)
      )

  private def deprecationLine(message: String): String =
    s"@deprecated(${quote(message)}, \"\")"

  private def quote(value: String): String =
    "\"" + value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n") + "\""
