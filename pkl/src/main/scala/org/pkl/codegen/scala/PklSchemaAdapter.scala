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

    private def convertClass(pClass: PClass, isModuleClass: Boolean): IrClass =
      val name =
        if isModuleClass then nameMapper.map(pClass.getModuleName)._2
        else pClass.getSimpleName
      val superclass =
        Option(pClass.getSuperclass).filterNot(isSkippedSuperclass)
      val superProps = superclass
        .map(_.getAllProperties.asScala.toList.filterNot(_._2.isHidden))
        .getOrElse(Nil)
        .map((name, prop) => convertProperty(name, prop))
      val ownProps = pClass.getProperties.asScala.toList
        .filterNot(_._2.isHidden)
        .map((name, prop) => convertProperty(name, prop))
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

    private def convertTypeParam(typeParam: TypeParameter): IrTypeParam =
      val variance = typeParam.getVariance match
        case TypeParameter.Variance.COVARIANT     => IrVariance.Covariant
        case TypeParameter.Variance.CONTRAVARIANT => IrVariance.Contravariant
        case _                                    => IrVariance.Invariant
      IrTypeParam(typeParam.getName, variance)

    private def isSkippedSuperclass(superclass: PClass): Boolean =
      superclass.getInfo == PClassInfo.Typed || superclass.getInfo == PClassInfo.Module

    private def convertCopy(pClass: PClass): IrCopy =
      val allSize = pClass.getAllProperties.asScala.count(!_._2.isHidden)
      val withDefaults =
        !hasSubclassInModule(pClass) &&
          Option(pClass.getSuperclass).forall(_.getModuleName == moduleName)
      val mainOverride = inheritsCopyMethodWithSameArity(pClass, allSize)
      IrCopy(mainOverride, withDefaults, ancestorOverrides(pClass))

    private def ancestorOverrides(pClass: PClass): List[List[IrProperty]] =
      @scala.annotation.tailrec
      def loop(
          current: PClass,
          isFirst: Boolean,
          smallestSeen: Int,
          collected: List[List[IrProperty]]
      ): List[List[IrProperty]] =
        Option(current) match
          case None                                  => collected
          case Some(concrete) if concrete.isAbstract =>
            loop(concrete.getSuperclass, false, smallestSeen, collected)
          case Some(concrete) =>
            val visible =
              concrete.getAllProperties.asScala.toList.filterNot(_._2.isHidden)
            if visible.size < smallestSeen then
              val updated =
                if isFirst then collected
                else
                  collected :+ visible.map((name, prop) =>
                    convertProperty(name, prop)
                  )
              loop(concrete.getSuperclass, false, visible.size, updated)
            else loop(concrete.getSuperclass, false, smallestSeen, collected)
      loop(pClass, true, Int.MaxValue, Nil)

    private def inheritsCopyMethodWithSameArity(
        pClass: PClass,
        allSize: Int
    ): Boolean =
      firstConcreteAncestor(pClass.getSuperclass)
        .exists(_.getAllProperties.asScala.count(!_._2.isHidden) == allSize)

    @scala.annotation.tailrec
    private def firstConcreteAncestor(candidate: PClass): Option[PClass] =
      Option(candidate) match
        case None                                   => None
        case Some(concrete) if !concrete.isAbstract => Some(concrete)
        case Some(abstractClass)                    =>
          firstConcreteAncestor(abstractClass.getSuperclass)

    private def hasSubclassInModule(pClass: PClass): Boolean =
      val all = schema.getModuleClass :: schema.getClasses.values.asScala.toList
      all.exists(candidate =>
        (candidate ne pClass) && isTransitiveSubclass(candidate, pClass)
      )

    @scala.annotation.tailrec
    private def isTransitiveSubclass(child: PClass, ancestor: PClass): Boolean =
      Option(child.getSuperclass) match
        case None                               => false
        case Some(parent) if parent eq ancestor => true
        case Some(parent) => isTransitiveSubclass(parent, ancestor)

    private def convertDecl(typeAlias: TypeAlias): IrModuleDecl =
      typeAlias.getAliasedType match
        case _: PType.Alias => IrModuleDecl.Alias(convertAlias(typeAlias))
        case aliased        =>
          val literals = new LinkedHashSet[String]()
          if CodeGeneratorUtils.isRepresentableAsEnum(aliased, literals)
          then
            IrModuleDecl.Enum(convertEnum(typeAlias, literals.asScala.toList))
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

    private def convertType(tpe: PType): IrType =
      tpe match
        case PType.UNKNOWN => IrType.Any
        case PType.NOTHING => IrType.Nothing
        case PType.MODULE  =>
          throw ScalaCodeGeneratorException(
            s"Pkl `$tpe` types are not supported by the Scala code generator."
          )
        case _: PType.StringLiteral => IrType.Str
        case c: PType.Class         => classType(c)
        case n: PType.Nullable    => IrType.OptionOf(convertType(n.getBaseType))
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

    private def classType(classTpe: PType.Class): IrType =
      val args = classTpe.getTypeArguments.asScala.toList.map(convertType)
      def anyIfEmpty: IrType = args.headOption.getOrElse(IrType.Any)
      classTpe.getPClass.getInfo match
        case PClassInfo.Any                        => IrType.Any
        case PClassInfo.Typed | PClassInfo.Dynamic => IrType.Any
        case PClassInfo.Boolean                    => IrType.Bool
        case PClassInfo.String                     => IrType.Str
        case PClassInfo.Number | PClassInfo.Float  => IrType.Double
        case PClassInfo.Int                        => IrType.Long
        case PClassInfo.Duration                   => IrType.Duration
        case PClassInfo.DataSize                   => IrType.DataSize
        case PClassInfo.Bytes                      => IrType.Bytes
        case PClassInfo.Pair                       =>
          IrType.PairType(anyIfEmpty, args.lift(1).getOrElse(IrType.Any))
        case PClassInfo.Collection => IrType.IterableOf(anyIfEmpty)
        case PClassInfo.List | PClassInfo.Listing =>
          IrType.ListOf(anyIfEmpty)
        case PClassInfo.Set                      => IrType.SetOf(anyIfEmpty)
        case PClassInfo.Map | PClassInfo.Mapping =>
          IrType.MapOf(anyIfEmpty, args.lift(1).getOrElse(IrType.Any))
        case PClassInfo.Module                    => IrType.PModule
        case PClassInfo.Class                     => IrType.PClass
        case PClassInfo.Regex                     => IrType.Regex
        case PClassInfo.Version                   => IrType.Version
        case info if !info.isStandardLibraryClass =>
          classRef(classTpe.getPClass, args)
        case _ =>
          throw ScalaCodeGeneratorException(
            s"Standard library class `${classTpe.getPClass.getQualifiedName}` is not supported by Scala code generator. " +
              "If you think this is an omission, please let us know."
          )

    private def aliasType(aliasTpe: PType.Alias): IrType =
      val alias = aliasTpe.getTypeAlias
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
          val args = aliasTpe.getTypeArguments.asScala.toList
          val finalArgs = (params, args) match
            case (Nil, _)      => Nil
            case (_, Nil)      => List.fill(params.size)(IrType.Any)
            case (_, provided) => provided.map(convertType)
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

    private def deprecationMessage(
        annotations: java.util.List[PObject]
    ): Option[String] =
      annotations.asScala.find(_.getClassInfo == PClassInfo.Deprecated).map {
        deprecation =>
          deprecation.getProperties.get("message") match
            case s: String => s
            case _         => ""
      }
