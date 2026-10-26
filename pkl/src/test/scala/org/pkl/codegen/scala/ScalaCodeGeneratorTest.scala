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
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSchema
import org.pkl.core.ModuleSource

import java.nio.file.Files
import java.nio.file.Path

class ScalaCodeGeneratorTest extends FunSuite:

  private def schemaFor(modules: (String, String)*): ModuleSchema =
    val dir = Files.createTempDirectory("pkl-scala-test")
    var last: Path = null
    for (name, content) <- modules do
      val file = dir.resolve(name.replace('.', '/') + ".pkl")
      Files.createDirectories(file.getParent)
      Files.writeString(file, content)
      last = file
    val evaluator = Evaluator.preconfigured()
    try evaluator.evaluateSchema(ModuleSource.path(last))
    finally evaluator.close()

  private def generate(
      options: ScalaCodeGeneratorOptions,
      modules: (String, String)*
  ): Map[String, String] =
    ScalaCodeGenerator(schemaFor(modules*), options).output

  private def scalaSource(files: Map[String, String]): String =
    files.collectFirst {
      case (name, content) if name.endsWith(".scala") => content
    }.get

  test("empty module generates object") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.mod" -> "module my.mod\n"
    )
    val src = scalaSource(files)
    assert(src.contains("package my"), src)
    assert(src.contains("object Mod"), src)
    assert(
      files.keys.exists(_.startsWith("resources/")),
      files.keys.mkString(",")
    )
  }

  test("simple module generates case classes with Option for nullable") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.birds" ->
        """module my.birds
          |pigeon: Person
          |parrot: String
          |count: Int
          |maybe: String?
          |tags: List<String>
          |scores: Map<String, Int>
          |class Person {
          |  name: String
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("package my"), src)
    assert(src.contains("case class Birds(pigeon: Birds.Person"), src)
    assert(src.contains("object Birds:"), src)
    assert(src.contains("parrot: String"), src)
    assert(src.contains("count: Long"), src)
    assert(src.contains("maybe: Option[String]"), src)
    assert(src.contains("tags: List[String]"), src)
    assert(src.contains("scores: Map[String, Long]"), src)
    assert(src.contains("case class Person("), src)
  }

  test("property type mappings") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.types" ->
        """module my.types
          |b: Boolean
          |i: Int
          |f: Float
          |n: Number
          |s: String
          |d: Duration
          |ds: DataSize
          |pair: Pair<String, Int>
          |coll: Collection<String>
          |set: Set<String>
          |bytes: Bytes
          |regex: Regex
          |any: Any
          |nonNull: NonNull
          |listing: Listing<String>
          |mapping: Mapping<String, Int>
          |""".stripMargin
    )
    val src = scalaSource(files)
    for expected <- List(
        "b: Boolean",
        "i: Long",
        "f: Double",
        "n: Double",
        "s: String",
        "d: Duration",
        "ds: DataSize",
        "pair: (String, Long)",
        "coll: Iterable[String]",
        "set: Set[String]",
        "bytes: Array[Byte]",
        "regex: Regex",
        "any: Any",
        "nonNull: Any",
        "listing: List[String]",
        "mapping: Map[String, Long]"
      )
    do assert(src.contains(expected), s"missing `$expected` in:\n$src")
    assert(src.contains("import org.pkl.core.DataSize"), src)
    assert(src.contains("import org.pkl.core.Duration"), src)
    assert(src.contains("import scala.util.matching.Regex"), src)
  }

  test("string literal union generates enum") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.birds" ->
        """module my.birds
          |bird: BirdKind
          |typealias BirdKind = "Pigeon"|"Barn Owl"|"Parrot"
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("enum BirdKind(val value: String):"), src)
    assert(src.contains("case PIGEON extends BirdKind(\"Pigeon\")"), src)
    assert(src.contains("case BARN_OWL extends BirdKind(\"Barn Owl\")"), src)
    assert(src.contains("override def toString: String = value"), src)
    assert(src.contains("bird: BirdKind"), src)
  }

  test("conflicting enum constants throw") {
    intercept[ScalaCodeGeneratorException] {
      generate(
        ScalaCodeGeneratorOptions(),
        "my.bad" ->
          """module my.bad
            |x: A
            |typealias A = "foo-bar"|"foo bar"
            |""".stripMargin
      )
    }
  }

  test("non-string union throws") {
    val err = intercept[ScalaCodeGeneratorException] {
      generate(
        ScalaCodeGeneratorOptions(),
        "my.bad" ->
          """module my.bad
            |x: Int|Boolean
            |""".stripMargin
      )
    }
    assert(err.getMessage.contains("union"), err.getMessage)
  }

  test("function type throws") {
    intercept[ScalaCodeGeneratorException] {
      generate(
        ScalaCodeGeneratorOptions(),
        "my.bad" ->
          """module my.bad
            |class Fn {
            |  f: (String) -> String
            |}
            |""".stripMargin
      )
    }
  }

  test("open and abstract classes generate hierarchy with copy") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.h" ->
        """module my.h
          |open class Foo {
          |  one: Int
          |}
          |open class Bar extends Foo {
          |  two: String
          |}
          |abstract class Baz extends Bar {
          |  three: Int
          |}
          |class Qux extends Baz {
          |  four: Int
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("object H:"), src)
    assert(src.contains("open class Foo("), src)
    assert(src.contains("val one: Long"), src)
    assert(src.contains("open class Bar("), src)
    assert(src.contains("extends H.Foo(one)"), src)
    assert(src.contains("abstract class Baz("), src)
    assert(src.contains("open class Qux("), src)
    assert(src.contains("def copy("), src)
    assert(src.contains("override def equals"), src)
    assert(src.contains("override def hashCode"), src)
    assert(src.contains("override def toString"), src)
    // Scala forbids two overloads with defaults in one scope (inherited members count), so only
    // leaf copies get defaults: Foo/Bar are non-leaf, Qux is the leaf.
    assert(src.contains("def copy(one: Long): Foo = Foo(one)"), src)
    assert(!src.contains("def copy(one: Long = this.one): Foo"), src)
    assert(
      src.contains(
        "def copy(one: Long = this.one, two: String = this.two, three: Long = this.three, four: Long = this.four): Qux"
      ),
      src
    )
  }

  test("bytes and regex get custom equals and hashCode") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.br" ->
        """module my.br
          |class Data {
          |  bytes: Bytes
          |  regex: Regex
          |  name: String
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("case class Data("), src)
    assert(src.contains("java.util.Arrays.equals"), src)
    assert(src.contains("java.util.Arrays.hashCode"), src)
    assert(src.contains(".pattern"), src)
    assert(src.contains("java.util.Arrays.toString"), src)
  }

  test("keywords are backticked") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.kw" ->
        """module my.kw
          |class K {
          |  `type`: String
          |  `val`: Int
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("`type`: String"), src)
    assert(src.contains("`val`: Long"), src)
  }

  test("hidden properties are omitted") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.hd" ->
        """module my.hd
          |class H {
          |  visible: String
          |  hidden secret: String
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("visible: String"), src)
    assert(!src.contains("secret"), src)
  }

  test("generic typealiases keep type parameters with variance") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.g" ->
        """module my.g
          |typealias StringMap<V> = Mapping<String, V>
          |m: StringMap<Int>
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("type StringMap[V] = Map[String, V]"), src)
    assert(src.contains("m: StringMap[Long]"), src)
  }

  test("constrained and stdlib aliases map to JVM types") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.c" ->
        """module my.c
          |import "pkl:semver"
          |a: UInt8
          |b: Int32
          |c: UInt
          |u: Uri
          |v: semver.Version
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("a: Short"), src)
    assert(src.contains("b: Int"), src)
    assert(src.contains("c: Long"), src)
    assert(src.contains("u: URI"), src)
    assert(src.contains("v: Version"), src)
  }

  test("deprecated annotations are emitted") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.d" ->
        """module my.d
          |@Deprecated { message = "do not use" }
          |class Old {
          |  x: String
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("@deprecated(\"do not use\", \"\")"), src)
  }

  test("scaladoc is emitted when enabled") {
    val pkl =
      """module my.doc
        |/// Module prop comment.
        |x: String
        |/// Class comment.
        |class C {
        |  /// Prop comment.
        |  y: String
        |}
        |""".stripMargin
    val withDoc = scalaSource(
      generate(
        ScalaCodeGeneratorOptions(generateScaladoc = true),
        "my.doc" -> pkl
      )
    )
    assert(withDoc.contains("/**"), withDoc)
    assert(withDoc.contains("Prop comment."), withDoc)
    assert(withDoc.contains("object Doc:"), withDoc)
    val withoutDoc =
      scalaSource(generate(ScalaCodeGeneratorOptions(), "my.doc" -> pkl))
    assert(!withoutDoc.contains("Prop comment."), withoutDoc)
  }

  test("renames remap package and class") {
    val files = generate(
      ScalaCodeGeneratorOptions(renames = Map("my." -> "com.example.")),
      "my.doc" -> "module my.doc\nx: String\n"
    )
    val src = scalaSource(files)
    assert(src.contains("package com.example"), src)
    assert(
      files.keys.exists(_.startsWith("scala/com/example/")),
      files.keys.mkString(",")
    )
  }

  test("serializable and generated options") {
    val files = generate(
      ScalaCodeGeneratorOptions(
        implementSerializable = true,
        addGeneratedAnnotation = true
      ),
      "my.sg" ->
        """module my.sg
          |class C {
          |  x: String
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assert(src.contains("extends Serializable"), src)
    assert(src.contains("@Generated"), src)
    assert(src.contains("javax.annotation.processing.Generated"), src)
  }

  test("properties files contain qualified mappings") {
    import java.io.StringReader
    import java.util.Properties
    def load(content: String): Properties =
      val props = new Properties()
      props.load(new StringReader(content))
      props
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.mod" ->
        """module my.mod
          |class Foo {
          |  x: String
          |}
          |""".stripMargin
    )
    val scalaProps = load(files.collectFirst {
      case (name, content) if name.contains("config/scala/mapper") => content
    }.get)
    assertEquals(
      scalaProps.getProperty("org.pkl.config.scala.mapper.my.mod#ModuleClass"),
      "my.Mod"
    )
    assertEquals(
      scalaProps.getProperty("org.pkl.config.scala.mapper.my.mod#Foo"),
      "my.Mod$Foo"
    )
    val javaProps = load(files.collectFirst {
      case (name, content) if name.contains("config/java/mapper") => content
    }.get)
    assertEquals(
      javaProps.getProperty("org.pkl.config.java.mapper.my.mod#Foo"),
      "my.Mod$Foo"
    )
  }

  test("stdlib modules are rejected") {
    val evaluator = Evaluator.preconfigured()
    try
      val schema = evaluator.evaluateSchema(
        ModuleSource.uri(java.net.URI.create("pkl:base"))
      )
      val err = intercept[ScalaCodeGeneratorException] {
        ScalaCodeGenerator(schema, ScalaCodeGeneratorOptions()).scalaFile
      }
      assert(err.getMessage.contains("standard library"), err.getMessage)
    finally evaluator.close()
  }

  test("Main.parse handles flags") {
    val result = Main.parse(
      Array(
        "--output-dir",
        "/tmp/x",
        "--generate-scaladoc",
        "--rename",
        "a=b",
        "foo.pkl"
      )
    )
    assert(result.isRight, result.toString)
    val opts = result.toOption.get
    assertEquals(opts.generateScaladoc, true)
    assertEquals(opts.renames, Map("a" -> "b"))
    assert(Main.parse(Array.empty).isLeft)
    assert(Main.parse(Array("--bogus", "x.pkl")).isLeft)
  }
