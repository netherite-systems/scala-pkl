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
package org.pkl.codegen.scala.ir

/** Pure intermediate representation of a Pkl module schema.
  *
  * The IR is the portability boundary: it contains only Scala data (no pkl-core
  * types), so the [[org.pkl.codegen.scala.ScalaRenderer]] that consumes it
  * cross-builds for JVM, Scala.js and Scala Native. The JVM-only
  * `PklSchemaAdapter` translates `ModuleSchema` into this model, resolving
  * everything that needs pkl-core utilities (enum detection, union checks,
  * renames).
  *
  * All names are stored raw; the renderer escapes them for Scala.
  */
enum IrVariance:
  case Invariant, Covariant, Contravariant

enum IrType:
  case Any, Nothing
  case Str, Bool, Long, Double, Byte, Short, Int
  case Duration, DataSize, DurationUnit, DataSizeUnit, Uri, Version, PModule,
    PClass
  case Bytes, Regex
  case PairType(first: IrType, second: IrType)
  case IterableOf(elem: IrType)
  case ListOf(elem: IrType)
  case SetOf(elem: IrType)
  case MapOf(key: IrType, value: IrType)
  case OptionOf(base: IrType)

  /** Reference to a user-defined class or type alias. `pkg` is the mapped
    * package.
    */
  case Ref(pkg: String, name: String, args: List[IrType])
  case TypeVar(name: String)

final case class IrTypeParam(name: String, variance: IrVariance)

final case class IrProperty(
    name: String,
    tpe: IrType,
    doc: Option[String],
    /** Deprecation message, if `@Deprecated`. */
    deprecated: Option[String]
)

enum IrClassKind:
  case Case, Open, Abstract

/** Precomputed `copy` methods for an open class.
  *
  * @param mainOverride
  *   whether the main copy overrides an inherited one with the same arity
  * @param withDefaults
  *   whether the main copy gets `= this.x` defaults (only when no other visible
  *   copy has defaults; Scala rejects two overloads that both define them)
  * @param overrides
  *   ancestor-arity overrides (no defaults), narrowest first
  */
final case class IrCopy(
    mainOverride: Boolean,
    withDefaults: Boolean,
    overrides: List[List[IrProperty]]
)

final case class IrClass(
    name: String,
    doc: Option[String],
    deprecated: Option[String],
    generated: Boolean,
    typeParams: List[IrTypeParam],
    kind: IrClassKind,
    /** Base type (with its own type arguments); `None` for root classes. */
    superType: Option[IrType],
    /** Constructor arguments forwarded to the superclass, in order. */
    superArgNames: List[String],
    ownProps: List[IrProperty],
    superProps: List[IrProperty],
    serializable: Boolean,
    /** `None` for abstract classes. */
    copy: Option[IrCopy]
):
  def allProps: List[IrProperty] = superProps ++ ownProps

final case class IrEnumCase(constName: String, literal: String)

final case class IrEnum(
    name: String,
    doc: Option[String],
    deprecated: Option[String],
    generated: Boolean,
    cases: List[IrEnumCase]
)

final case class IrTypeAlias(
    name: String,
    doc: Option[String],
    deprecated: Option[String],
    generated: Boolean,
    params: List[IrTypeParam],
    rhs: IrType
)

enum IrModuleDecl:
  case Enum(e: IrEnum)
  case Alias(a: IrTypeAlias)

final case class IrModule(
    packageName: String,
    moduleTypeName: String,
    /** `None` when the module has no properties (renders as `object Mod`). */
    moduleClass: Option[IrClass],
    classes: List[IrClass],
    decls: List[IrModuleDecl]
)
