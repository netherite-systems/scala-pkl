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

import munit.FunSuite
import org.pkl.codegen.scala.ir._

/** Renderer tests run on every platform (JVM, Scala.js, Scala Native): they use
  * hand-built IR and never touch the Pkl evaluator.
  */
class ScalaRendererTest extends FunSuite:

  private val options = ScalaCodeGeneratorOptions()

  private def render(module: IrModule): String =
    ScalaRenderer(options).renderModule(module)

  private def prop(name: String, tpe: IrType): IrProperty =
    IrProperty(name, tpe, None, None)

  test("empty module renders object") {
    val src = render(IrModule("my", "Mod", None, Nil, Nil))
    assert(src.contains("package my"), src)
    assert(src.contains("object Mod"), src)
  }

  test("case class with option and collections") {
    val cls = IrClass(
      "Birds",
      None,
      None,
      false,
      Nil,
      IrClassKind.Case,
      None,
      Nil,
      List(
        prop("parrot", IrType.Str),
        prop("maybe", IrType.OptionOf(IrType.Str)),
        prop("tags", IrType.ListOf(IrType.Str)),
        prop("scores", IrType.MapOf(IrType.Str, IrType.Long))
      ),
      Nil,
      false,
      None
    )
    val src = render(IrModule("my", "Birds", Some(cls), Nil, Nil))
    assert(
      src.contains(
        "case class Birds(parrot: String, maybe: Option[String], tags: List[String], scores: Map[String, Long])"
      ),
      src
    )
  }

  test("foreign references import, clashes qualify") {
    val cls = IrClass(
      "M",
      None,
      None,
      false,
      Nil,
      IrClassKind.Case,
      None,
      Nil,
      List(
        prop("one", IrType.Ref("com.foo", "Person", Nil)),
        prop("two", IrType.Ref("com.bar", "Person", Nil))
      ),
      Nil,
      false,
      None
    )
    val src = render(IrModule("my", "M", Some(cls), Nil, Nil))
    // first use wins the import; the clash renders fully qualified
    assert(src.contains("import com.foo.Person"), src)
    assert(src.contains("one: Person"), src)
    assert(src.contains("two: com.bar.Person"), src)
  }

  test("open hierarchy honors copy flags") {
    val foo = IrClass(
      "Foo",
      None,
      None,
      false,
      Nil,
      IrClassKind.Open,
      None,
      Nil,
      List(prop("one", IrType.Long)),
      Nil,
      false,
      Some(IrCopy(mainOverride = false, withDefaults = false, overrides = Nil))
    )
    val bar = IrClass(
      "Bar",
      None,
      None,
      false,
      Nil,
      IrClassKind.Open,
      Some(IrType.Ref("", "Foo", Nil)),
      List("one"),
      List(prop("two", IrType.Str)),
      List(prop("one", IrType.Long)),
      false,
      Some(
        IrCopy(
          mainOverride = false,
          withDefaults = true,
          overrides = List(List(prop("one", IrType.Long)))
        )
      )
    )
    val src = render(IrModule("my", "H", None, List(foo, bar), Nil))
    assert(
      src.contains(
        "open class Bar(one: Long, val two: String) extends Foo(one):"
      ),
      src
    )
    assert(
      src.contains(
        "def copy(one: Long = this.one, two: String = this.two): Bar = Bar(one, two)"
      ),
      src
    )
    assert(
      src.contains("override def copy(one: Long): Bar = Bar(one, two)"),
      src
    )
    assert(src.contains("def copy(one: Long): Foo = Foo(one)"), src)
  }

  test("bytes and regex get structural equality") {
    val cls = IrClass(
      "Data",
      None,
      None,
      false,
      Nil,
      IrClassKind.Case,
      None,
      Nil,
      List(prop("bytes", IrType.Bytes), prop("regex", IrType.Regex)),
      Nil,
      false,
      None
    )
    val src = render(IrModule("", "M", Some(cls), Nil, Nil))
    assert(!src.contains("package "), src)
    assert(src.contains("java.util.Arrays.equals(this.bytes, o.bytes)"), src)
    assert(src.contains("this.regex.pattern != o.regex.pattern"), src)
    assert(src.contains("import scala.util.matching.Regex"), src)
  }

  test("enum and generic typealias") {
    val decls = List(
      IrModuleDecl.Enum(
        IrEnum(
          "Kind",
          None,
          None,
          false,
          List(IrEnumCase("WEB", "web"), IrEnumCase("BARN_OWL", "Barn Owl"))
        )
      ),
      IrModuleDecl.Alias(
        IrTypeAlias(
          "StringMap",
          None,
          None,
          false,
          List(IrTypeParam("V", IrVariance.Invariant)),
          IrType.MapOf(IrType.Str, IrType.TypeVar("V"))
        )
      )
    )
    val src = render(IrModule("my", "M", None, Nil, decls))
    assert(src.contains("enum Kind(val value: String):"), src)
    assert(src.contains("case BARN_OWL extends Kind(\"Barn Owl\")"), src)
    assert(src.contains("override def toString: String = value"), src)
    assert(src.contains("type StringMap[V] = Map[String, V]"), src)
  }

  test("keywords backticked, docs and annotations gated") {
    val cls = IrClass(
      "C",
      Some("Class comment."),
      Some("do not use"),
      true,
      Nil,
      IrClassKind.Case,
      None,
      Nil,
      List(IrProperty("type", IrType.Str, Some("Prop comment."), None)),
      Nil,
      true,
      None
    )
    val withDoc = ScalaRenderer(
      ScalaCodeGeneratorOptions(
        generateScaladoc = true,
        addGeneratedAnnotation = true
      )
    )
      .renderModule(IrModule("my", "M", Some(cls), Nil, Nil))
    assert(withDoc.contains("/** Class comment. */"), withDoc)
    assert(withDoc.contains("/** Prop comment. */ `type`: String"), withDoc)
    assert(withDoc.contains("@deprecated(\"do not use\", \"\")"), withDoc)
    assert(withDoc.contains("@Generated"), withDoc)
    assert(withDoc.contains("extends Serializable"), withDoc)

    val plain = render(IrModule("my", "M", Some(cls), Nil, Nil))
    assert(!plain.contains("Class comment."), plain)
    assert(plain.contains("`type`: String"), plain)
  }
