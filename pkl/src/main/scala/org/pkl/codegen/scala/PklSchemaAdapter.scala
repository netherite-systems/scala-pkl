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

import org.pkl.codegen.scala.internal.NameMapper
import org.pkl.codegen.scala.ir._
import org.pkl.core._
import org.pkl.core.util.CodeGeneratorUtils

import java.util.LinkedHashSet
import scala.jdk.CollectionConverters._

/** Translates a pkl-core `ModuleSchema` into the pure [[ir.IrModule]].
  *
  * JVM-only: this is where every pkl-core utility lives (`CodeGeneratorUtils`
  * enum/union checks, `NameMapper` renames, hidden filtering, declaration-order
  * preservation, copy analysis). The resulting IR is rendered by the
  * cross-built [[ScalaRenderer]].
  */
object PklSchemaAdapter:

  def adapt(
      schema: ModuleSchema,
      options: ScalaCodeGeneratorOptions
  ): IrModule =
    new Adapter(schema, options).adaptModule()

  private final class Adapter(
      schema: ModuleSchema,
      options: ScalaCodeGeneratorOptions
  ):
    private val nameMapper = NameMapper(options.renames)
    private val moduleName = schema.getModuleName
    private val (packageName, moduleTypeName) = nameMapper.map(moduleName)

    def adaptModule(): IrModule =
      val moduleClass = schema.getModuleClass
      val hasModuleProperties =
        moduleClass.getProperties.values.asScala.exists(!_.isHidden)
      val isGenerateModuleClass =
        hasModuleProperties || moduleClass.isOpen || moduleClass.isAbstract
      IrModule(
        packageName,
        moduleTypeName,
        Option(moduleClass.getDocComment),
        if isGenerateModuleClass then
          Some(convertClass(moduleClass, isModuleClass = true))
        else None,
        schema.getClasses.values.asScala.toList
          .map(convertClass(_, isModuleClass = false)),
        schema.getTypeAliases.values.asScala.toList.map(convertDecl)
      )

    // ----------------------------------------------------------------------
    // classes
    // ----------------------------------------------------------------------

    private def convertClass(pClass: PClass, isModuleClass: Boolean): IrClass =
      val name =
        if isModuleClass then nameMapper.map(pClass.getModuleName)._2
        else pClass.getSimpleName
      val superclass =
        Option(pClass.getSuperclass).filterNot(isSkippedSuperclass)
      // Materialize to List BEFORE filtering: filterNot on a mutable.Map view rebuilds through
      // the unordered HashMap factory and loses declaration order.
      val superProps = superclass
        .map(_.getAllProperties.asScala.toList.filterNot(_._2.isHidden))
        .getOrElse(Nil)
        .map((n, p) => convertProperty(n, p))
      val ownProps = pClass.getProperties.asScala.toList
        .filterNot(_._2.isHidden)
        .map((n, p) => convertProperty(n, p))
      val superType: Option[IrType] = superclass.map { _ =>
        Option(pClass.getSupertype)
          .collect { case c: PType.Class => convertType(c) }
          .getOrElse(classRef(pClass.getSuperclass))
      }
      val kind =
        if superclass.isEmpty && !pClass.isAbstract && !pClass.isOpen then
          IrClassKind.Case
        else if pClass.isAbstract then IrClassKind.Abstract
        else IrClassKind.Open
      IrClass(
        name,
        Option(pClass.getDocComment),
        deprecationMessage(pClass.getAnnotations),
        options.addGeneratedAnnotation,
        pClass.getTypeParameters.asScala.toList.map(convertTypeParam),
        kind,
        superType,
        superProps.map(_.name),
        ownProps,
        superProps,
        options.implementSerializable && !pClass.isAbstract,
        if pClass.isAbstract then None else Some(convertCopy(pClass))
      )

    private def convertProperty(
        name: String,
        prop: PClass.Property
    ): IrProperty =
      IrProperty(
        name,
        convertType(prop.getType),
        Option(prop.getDocComment),
        deprecationMessage(prop.getAnnotations)
      )

    private def convertTypeParam(tp: TypeParameter): IrTypeParam =
      val variance = tp.getVariance match
        case TypeParameter.Variance.COVARIANT     => IrVariance.Covariant
        case TypeParameter.Variance.CONTRAVARIANT => IrVariance.Contravariant
        case _                                    => IrVariance.Invariant
      IrTypeParam(tp.getName, variance)

    private def isSkippedSuperclass(sc: PClass): Boolean =
      sc.getInfo == PClassInfo.Typed || sc.getInfo == PClassInfo.Module

    // ----------------------------------------------------------------------
    // copy analysis (ported from KotlinCodeGenerator; see ScalaRenderer for why)
    // ----------------------------------------------------------------------

    private def convertCopy(pClass: PClass): IrCopy =
      val allSize = pClass.getAllProperties.asScala.count(!_._2.isHidden)
      // Scala rejects two overloads that both define default arguments (inherited members
      // count). Defaults go on the main copy only when no other visible copy has them.
      val withDefaults =
        !hasSubclassInModule(pClass) &&
          Option(pClass.getSuperclass).forall(_.getModuleName == moduleName)
      val mainOverride = inheritsCopyMethodWithSameArity(pClass, allSize)
      // ancestor-arity overrides (no defaults), narrowest first
      var overrides = List.empty[List[IrProperty]]
      var prevCount = Int.MaxValue
      var current: PClass = pClass
      var first = true
      while current != null do
        if !current.isAbstract then
          val currProps =
            current.getAllProperties.asScala.toList.filterNot(_._2.isHidden)
          if currProps.size < prevCount then
            if !first then
              overrides =
                overrides :+ currProps.map((n, p) => convertProperty(n, p))
            prevCount = currProps.size
        first = false
        current = current.getSuperclass
      IrCopy(mainOverride, withDefaults, overrides)

    private def inheritsCopyMethodWithSameArity(
        pClass: PClass,
        allSize: Int
    ): Boolean =
      var ancestor = pClass.getSuperclass
      while ancestor != null && ancestor.isAbstract do
        ancestor = ancestor.getSuperclass
      if ancestor == null then false
      else ancestor.getAllProperties.asScala.count(!_._2.isHidden) == allSize

    private def hasSubclassInModule(pClass: PClass): Boolean =
      val all = schema.getModuleClass :: schema.getClasses.values.asScala.toList
      all.exists(c => (c ne pClass) && isTransitiveSubclass(c, pClass))

    private def isTransitiveSubclass(c: PClass, ancestor: PClass): Boolean =
      var current = c.getSuperclass
      while current != null do
        if current eq ancestor then return true
        current = current.getSuperclass
      false

    // ----------------------------------------------------------------------
    // type aliases and enums
    // ----------------------------------------------------------------------

    private def convertDecl(typeAlias: TypeAlias): IrModuleDecl =
      if typeAlias.getAliasedType.isInstanceOf[PType.Alias] then
        IrModuleDecl.Alias(convertAlias(typeAlias))
      else
        val literals = new LinkedHashSet[String]()
        if CodeGeneratorUtils.isRepresentableAsEnum(
            typeAlias.getAliasedType,
            literals
          )
        then IrModuleDecl.Enum(convertEnum(typeAlias, literals.asScala.toList))
        else IrModuleDecl.Alias(convertAlias(typeAlias))

    private def convertEnum(
        typeAlias: TypeAlias,
        literals: List[String]
    ): IrEnum =
      val grouped = literals.groupBy(CodeGeneratorUtils.toEnumConstantName)
      for (constant, lits) <- grouped do
        if constant == null then
          throw ScalaCodeGeneratorException(
            s"Cannot generate Scala enum for Pkl type alias `${typeAlias.getDisplayName}` " +
              s"because string literal type \"${lits.head}\" cannot be converted to a valid enum constant name."
          )
        if lits.size > 1 then
          throw ScalaCodeGeneratorException(
            s"Cannot generate Scala enum for Pkl type alias `${typeAlias.getDisplayName}` " +
              s"because string literal types \"${lits(0)}\" and \"${lits(1)}\" " +
              s"would both be converted to enum constant name `$constant`."
          )
      IrEnum(
        typeAlias.getSimpleName,
        Option(typeAlias.getDocComment),
        deprecationMessage(typeAlias.getAnnotations),
        options.addGeneratedAnnotation,
        literals.map(lit =>
          IrEnumCase(CodeGeneratorUtils.toEnumConstantName(lit), lit)
        )
      )

    private def convertAlias(typeAlias: TypeAlias): IrTypeAlias =
      IrTypeAlias(
        typeAlias.getSimpleName,
        Option(typeAlias.getDocComment),
        deprecationMessage(typeAlias.getAnnotations),
        options.addGeneratedAnnotation,
        typeAlias.getTypeParameters.asScala.toList.map(convertTypeParam),
        convertType(typeAlias.getAliasedType)
      )

    // ----------------------------------------------------------------------
    // PType -> IrType (ported from PType.toKotlinPoetName)
    // ----------------------------------------------------------------------

    private def convertType(tpe: PType): IrType =
      if tpe == PType.UNKNOWN then IrType.Any
      else if tpe == PType.NOTHING then IrType.Nothing
      else if tpe == PType.MODULE then
        throw ScalaCodeGeneratorException(
          s"Pkl `$tpe` types are not supported by the Scala code generator."
        )
      else
        tpe match
          case _: PType.StringLiteral => IrType.Str
          case c: PType.Class         => classType(c)
          case n: PType.Nullable => IrType.OptionOf(convertType(n.getBaseType))
          case c: PType.Constrained => convertType(c.getBaseType)
          case a: PType.Alias       => aliasType(a)
          case _: PType.Function    =>
            throw ScalaCodeGeneratorException(
              "Pkl function types are not supported by the Scala code generator."
            )
          case u: PType.Union =>
            if CodeGeneratorUtils.isRepresentableAsString(u) then IrType.Str
            else
              throw ScalaCodeGeneratorException(
                "Pkl union types are not supported by the Scala code generator."
              )
          case v: PType.TypeVariable => IrType.TypeVar(v.getName)
          case _                     =>
            throw new AssertionError(
              s"Encountered unexpected PType subclass: $tpe"
            )

    private def classType(c: PType.Class): IrType =
      val args = c.getTypeArguments.asScala.toList.map(convertType)
      def anyIfEmpty: IrType = args.headOption.getOrElse(IrType.Any)
      val info = c.getPClass.getInfo
      if info == PClassInfo.Any then IrType.Any
      else if info == PClassInfo.Typed || info == PClassInfo.Dynamic then
        IrType.Any
      else if info == PClassInfo.Boolean then IrType.Bool
      else if info == PClassInfo.String then IrType.Str
      else if info == PClassInfo.Number || info == PClassInfo.Float then
        IrType.Double
      else if info == PClassInfo.Int then IrType.Long
      else if info == PClassInfo.Duration then IrType.Duration
      else if info == PClassInfo.DataSize then IrType.DataSize
      else if info == PClassInfo.Bytes then IrType.Bytes
      else if info == PClassInfo.Pair then
        IrType.PairType(anyIfEmpty, args.lift(1).getOrElse(IrType.Any))
      else if info == PClassInfo.Collection then IrType.IterableOf(anyIfEmpty)
      else if info == PClassInfo.List || info == PClassInfo.Listing then
        IrType.ListOf(anyIfEmpty)
      else if info == PClassInfo.Set then IrType.SetOf(anyIfEmpty)
      else if info == PClassInfo.Map || info == PClassInfo.Mapping then
        IrType.MapOf(anyIfEmpty, args.lift(1).getOrElse(IrType.Any))
      else if info == PClassInfo.Module then IrType.PModule
      else if info == PClassInfo.Class then IrType.PClass
      else if info == PClassInfo.Regex then IrType.Regex
      else if info == PClassInfo.Version then IrType.Version
      else if !info.isStandardLibraryClass then classRef(c.getPClass, args)
      else
        throw ScalaCodeGeneratorException(
          s"Standard library class `${c.getPClass.getQualifiedName}` is not supported by Scala code generator. " +
            "If you think this is an omission, please let us know."
        )

    private def aliasType(a: PType.Alias): IrType =
      val alias = a.getTypeAlias
      alias.getQualifiedName match
        case "pkl.base#NonNull"                   => IrType.Any
        case "pkl.base#Int8"                      => IrType.Byte
        case "pkl.base#Int16" | "pkl.base#UInt8"  => IrType.Short
        case "pkl.base#Int32" | "pkl.base#UInt16" => IrType.Int
        case "pkl.base#UInt" | "pkl.base#UInt32"  => IrType.Long
        case "pkl.base#DurationUnit"              => IrType.DurationUnit
        case "pkl.base#DataSizeUnit"              => IrType.DataSizeUnit
        case "pkl.base#Uri"                       => IrType.Uri
        case _                                    =>
          val (pkg, _) = nameMapper.map(alias.getModuleName)
          val params = alias.getTypeParameters.asScala.toList
          val args = a.getTypeArguments.asScala.toList
          val finalArgs =
            if params.isEmpty then Nil
            else if args.isEmpty then List.fill(params.size)(IrType.Any)
            else args.map(convertType)
          IrType.Ref(pkg, "", alias.getSimpleName, finalArgs)

    /** Reference to a user-defined Pkl class.
      *
      * Module classes are top-level (`Mod`); nested classes are always
      * qualified (`Mod.Person`, see `IrType.Ref`).
      */
    private def classRef(pClass: PClass, args: List[IrType] = Nil): IrType =
      val (pkg, wrapper) = nameMapper.map(pClass.getModuleName)
      if pClass.isModuleClass then IrType.Ref(pkg, "", wrapper, args)
      else IrType.Ref(pkg, wrapper, pClass.getSimpleName, args)

    // ----------------------------------------------------------------------
    // misc helpers
    // ----------------------------------------------------------------------

    private def deprecationMessage(
        annotations: java.util.List[PObject]
    ): Option[String] =
      annotations.asScala.find(_.getClassInfo == PClassInfo.Deprecated).map {
        d =>
          d.getProperties.get("message") match
            case s: String => s
            case _         => ""
      }
