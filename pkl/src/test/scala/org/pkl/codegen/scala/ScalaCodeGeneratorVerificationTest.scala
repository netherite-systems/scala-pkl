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
import org.pkl.codegen.scala.ScalaSourceCode._
import org.pkl.core.Evaluator
import org.pkl.core.ModuleSource

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Kotlin-parity verification: generated code must compile and behave
  * correctly.
  *
  * Mirrors `KotlinCodeGeneratorTest` layers: string assertions, real `dotc`
  * compilation (`assertCompilesSuccessfully`), reflective instantiation, and
  * multi-module `generateFiles` coverage.
  */
class ScalaCodeGeneratorVerificationTest extends FunSuite:

  private def schemaFor(modules: (String, String)*): org.pkl.core.ModuleSchema =
    val dir = Files.createTempDirectory("pkl-scala-verify")
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

  private def scalaFiles(files: Map[String, String]): Map[String, String] =
    files.filter((name, _) => name.endsWith(".scala"))

  private def scalaSource(files: Map[String, String]): String =
    files.collectFirst {
      case (name, content) if name.endsWith(".scala") => content
    }.get

  test("simple module compiles successfully") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.birds" ->
        """module my.birds
          |pigeon: Person
          |parrot: String
          |class Person {
          |  name: String
          |}
          |""".stripMargin
    )
    assertCompilesSuccessfully(scalaFiles(files))
  }

  test("property types match golden file and compile") {
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
    assertEqualToResourceFile(src, "PropertyTypes.scala")
    assertCompilesSuccessfully(scalaFiles(files))
  }

  test("property types instantiate with correct runtime values") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.types" ->
        """module my.types
          |b: Boolean
          |i: Int
          |s: String
          |maybe: String?
          |tags: List<String>
          |scores: Map<String, Int>
          |class Person {
          |  name: String
          |}
          |""".stripMargin
    )
    val compiled = assertCompilesSuccessfully(scalaFiles(files))
    val typesClass = compiled.getValue("my.Types")
    val personClass = compiled.getValue("Person")
    val person = personClass.getConstructors.head.newInstance("pigeon")
    val types = typesClass.getConstructors.head.newInstance(
      true.asInstanceOf[AnyRef],
      42L.asInstanceOf[AnyRef],
      "string",
      Option("idea"),
      List("a", "b"),
      Map("one" -> 1L)
    )
    assert(types.toString.contains("string"), types.toString)
    val maybeField = typesClass.getDeclaredField("maybe")
    maybeField.setAccessible(true)
    assertEquals(maybeField.get(types), Option("idea"))
    val tagsField = typesClass.getDeclaredField("tags")
    tagsField.setAccessible(true)
    assertEquals(tagsField.get(types), List("a", "b"))
    // nested class instantiates independently
    assertEquals(person.toString, "Person(pigeon)")
  }

  test("case class equals/hashCode/toString") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.mod" ->
        """module my.mod
          |class Simple {
          |  str: String
          |  list: List<Int>
          |}
          |""".stripMargin
    )
    val compiled = assertCompilesSuccessfully(scalaFiles(files))
    val clazz = compiled.getValue("Simple")
    val ctor = clazz.getConstructors.head
    val a = ctor.newInstance("foo", List(1L, 2L))
    val b = ctor.newInstance("foo", List(1L, 2L))
    val c = ctor.newInstance("foo", List(1L, 3L))
    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
    assertNotEquals(a, c)
    assert(a.toString.contains("foo"), a.toString)
  }

  test("string literal union generates working enum") {
    val files = generate(
      ScalaCodeGeneratorOptions(),
      "my.birds" ->
        """module my.birds
          |bird: BirdKind
          |typealias BirdKind = "Pigeon"|"Barn Owl"|"Parrot"
          |""".stripMargin
    )
    val src = scalaSource(files)
    assertContains(src, "enum BirdKind(val value: String):")
    assertContains(src, "case PIGEON extends BirdKind(\"Pigeon\")")
    val compiled = assertCompilesSuccessfully(scalaFiles(files))
    val enumClass = compiled.getValue("my.BirdKind")
    val values =
      enumClass.getMethod("values").invoke(null).asInstanceOf[Array[?]]
    assertEquals(values.length, 3)
    assertEquals(values.head.toString, "Pigeon")
  }

  test("open hierarchy copies and compares") {
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
          |class Qux extends Bar {
          |  three: Int
          |}
          |""".stripMargin
    )
    val src = scalaSource(files)
    assertContains(src, "open class Foo(")
    assertContains(src, "def copy(")
    val compiled = assertCompilesSuccessfully(scalaFiles(files))
    val quxClass = compiled.getValue("Qux")
    val ctor = quxClass.getConstructors.head
    val q1 =
      ctor.newInstance(1L.asInstanceOf[AnyRef], "a", 3L.asInstanceOf[AnyRef])
    val q2 =
      ctor.newInstance(1L.asInstanceOf[AnyRef], "a", 3L.asInstanceOf[AnyRef])
    val q3 =
      ctor.newInstance(1L.asInstanceOf[AnyRef], "b", 3L.asInstanceOf[AnyRef])
    assertEquals(q1, q2)
    assertNotEquals(q1, q3)
    val copy =
      quxClass.getMethod("copy", classOf[Long], classOf[String], classOf[Long])
    val q4 =
      copy.invoke(q1, 1L.asInstanceOf[AnyRef], "b", 3L.asInstanceOf[AnyRef])
    assertEquals(q4, q3)
  }

  test("bytes and regex use structural equality at runtime") {
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
    val compiled = assertCompilesSuccessfully(scalaFiles(files))
    val clazz = compiled.getValue("Data")
    val ctor = clazz.getConstructors.head
    // NOTE: like Kotlin's generator, Regex equality compares the underlying
    // `java.util.regex.Pattern` by reference, so distinct instances with the
    // same source are not equal. Bytes use structural `Arrays.equals`.
    val r = scala.util.matching.Regex("(i?)\\w*")
    val d1 = ctor.newInstance(Array[Byte](1, 2), r, "n")
    val d2 = ctor.newInstance(Array[Byte](1, 2), r, "n")
    val d3 = ctor.newInstance(Array[Byte](1, 3), r, "n")
    assertEquals(d1, d2)
    assertEquals(d1.hashCode, d2.hashCode)
    assertNotEquals(d1, d3)
  }

  test("serializable round-trip") {
    val files = generate(
      ScalaCodeGeneratorOptions(implementSerializable = true),
      "my.sg" ->
        """module my.sg
          |class C {
          |  x: String
          |  n: Int
          |}
          |""".stripMargin
    )
    val compiled = assertCompilesSuccessfully(scalaFiles(files))
    val clazz = compiled.getValue("C")
    val orig =
      clazz.getConstructors.head.newInstance("hi", 7L.asInstanceOf[AnyRef])
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(orig)
    oos.flush()
    val ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray)):
      override def resolveClass(desc: java.io.ObjectStreamClass): Class[?] =
        Class.forName(desc.getName, false, compiled.loader)
    val restored = ois.readObject()
    assertEquals(restored, orig)
  }

  test("multi-module import compiles together") {
    val dir = Files.createTempDirectory("pkl-scala-multi")
    val lib = dir.resolve("library.pkl")
    Files.writeString(
      lib,
      """module library
        |class Person { name: String; age: Int }
        |pigeon: Person
        |""".stripMargin
    )
    val client = dir.resolve("client.pkl")
    Files.writeString(
      client,
      """module client
        |import "library.pkl"
        |lib: library
        |parrot: library.Person
        |""".stripMargin
    )
    val evaluator = Evaluator.preconfigured()
    try
      val libSchema = evaluator.evaluateSchema(ModuleSource.path(lib))
      val clientSchema = evaluator.evaluateSchema(ModuleSource.path(client))
      val libFiles =
        ScalaCodeGenerator(libSchema, ScalaCodeGeneratorOptions()).output
      val clientFiles =
        ScalaCodeGenerator(clientSchema, ScalaCodeGeneratorOptions()).output
      // both modules are in the default package; compile their sources together
      // so `Client` resolves `Library` (like Kotlin's `generateFiles` helper).
      val merged = scalaFiles(libFiles) ++ scalaFiles(clientFiles)
      val compiled = assertCompilesSuccessfully(merged)
      assert(compiled.getValue("Library") != null)
      assert(compiled.getValue("Client") != null)
    finally evaluator.close()
  }

  test("mapper properties and renames") {
    val files = generate(
      ScalaCodeGeneratorOptions(renames = Map("my." -> "com.example.")),
      "my.doc" -> "module my.doc\nx: String\n"
    )
    assertContains(scalaSource(files), "package com.example")
    assert(
      files.keys.exists(_.startsWith("scala/com/example/")),
      files.keys.mkString(",")
    )
    assertCompilesSuccessfully(scalaFiles(files))
  }

  test("unsupported unions still throw") {
    intercept[ScalaCodeGeneratorException] {
      generate(
        ScalaCodeGeneratorOptions(),
        "my.bad" ->
          """module my.bad
            |x: Int|Boolean
            |""".stripMargin
      )
    }
  }
