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
import org.pkl.codegen.scala.ScalaSourceCode.assertCompilesSuccessfully

import java.nio.file.Files
import scala.jdk.CollectionConverters._

class CliScalaCodeGeneratorTest extends FunSuite:
  test("cli writes scala sources and mapper properties") {
    val dir = Files.createTempDirectory("pkl-scala-cli")
    val mod = dir.resolve("birds.pkl")
    Files.writeString(
      mod,
      """module birds
        |parrot: String
        |class Person {
        |  name: String
        |}
        |""".stripMargin
    )
    val outDir = dir.resolve("out")
    CliScalaCodeGenerator(
      CliScalaCodeGeneratorOptions(List(mod.toUri), outDir)
    ).run()
    val scalaFile = outDir.resolve("scala/Birds.scala")
    assert(Files.exists(scalaFile), s"missing $scalaFile")
    val content = Files.readString(scalaFile)
    assert(content.contains("case class Birds("), content)
    assert(content.contains("object Birds:"), content)
    assert(content.contains("case class Person("), content)
    assert(
      Files.exists(
        outDir.resolve(
          "resources/META-INF/org/pkl/config/scala/mapper/classes/birds.properties"
        )
      )
    )
    assert(
      Files.exists(
        outDir.resolve(
          "resources/META-INF/org/pkl/config/java/mapper/classes/birds.properties"
        )
      )
    )
  }

  test("cli handles module inheritance") {
    val dir = Files.createTempDirectory("pkl-scala-cli-inherit")
    val mod1 = dir.resolve("org/mod1.pkl")
    Files.createDirectories(mod1.getParent)
    Files.writeString(
      mod1,
      """open module org.mod1
        |
        |pigeon: Person
        |
        |class Person {
        |  name: String
        |  age: Int
        |}
        |""".stripMargin
    )
    val mod2 = dir.resolve("org/mod2.pkl")
    Files.writeString(
      mod2,
      """module org.mod2
        |
        |extends "mod1.pkl"
        |
        |parrot: Person
        |""".stripMargin
    )
    val outDir = dir.resolve("out")
    CliScalaCodeGenerator(
      CliScalaCodeGeneratorOptions(List(mod1.toUri, mod2.toUri), outDir)
    ).run()
    val mod1File = outDir.resolve("scala/org/Mod1.scala")
    val mod2File = outDir.resolve("scala/org/Mod2.scala")
    assert(Files.exists(mod1File), s"missing $mod1File")
    assert(Files.exists(mod2File), s"missing $mod2File")
    val mod2Content = Files.readString(mod2File)
    // derived module class extends the base module class
    assert(mod2Content.contains("Mod1"), mod2Content)
    assert(
      Files.exists(
        outDir.resolve(
          "resources/META-INF/org/pkl/config/scala/mapper/classes/org.mod1.properties"
        )
      )
    )
    assert(
      Files.exists(
        outDir.resolve(
          "resources/META-INF/org/pkl/config/scala/mapper/classes/org.mod2.properties"
        )
      )
    )
    // everything the CLI wrote must compile together
    val sources = Files
      .walk(outDir.resolve("scala"))
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".scala"))
      .map(p => outDir.relativize(p).toString -> Files.readString(p))
      .toMap
    assertCompilesSuccessfully(sources)
  }

  test("cli handles class name clashes") {
    val dir = Files.createTempDirectory("pkl-scala-cli-clash")
    val mod1 = dir.resolve("org/mod1.pkl")
    Files.createDirectories(mod1.getParent)
    Files.writeString(
      mod1,
      """module org.mod1
        |
        |class Person {
        |  name: String
        |}
        |""".stripMargin
    )
    val mod2 = dir.resolve("org/mod2.pkl")
    Files.writeString(
      mod2,
      """module org.mod2
        |
        |import "mod1.pkl"
        |
        |person1: mod1.Person
        |person2: Person
        |
        |class Person {
        |  age: Int
        |}
        |""".stripMargin
    )
    val outDir = dir.resolve("out")
    CliScalaCodeGenerator(
      CliScalaCodeGeneratorOptions(List(mod1.toUri, mod2.toUri), outDir)
    ).run()
    val mod2File = outDir.resolve("scala/org/Mod2.scala")
    assert(Files.exists(mod2File), s"missing $mod2File")
    val content = Files.readString(mod2File)
    // the imported Person must be qualified; the local one is not
    assert(content.contains("person1"), content)
    assert(content.contains("person2"), content)
  }

  test("cli handles custom package names") {
    val dir = Files.createTempDirectory("pkl-scala-cli-renames")
    val mod1 = dir.resolve("org/foo/Module1.pkl")
    Files.createDirectories(mod1.getParent)
    Files.writeString(
      mod1,
      """module org.foo.Module1
        |
        |class Person {
        |  name: String
        |}
        |""".stripMargin
    )
    val mod2 = dir.resolve("org/bar/Module2.pkl")
    Files.createDirectories(mod2.getParent)
    Files.writeString(
      mod2,
      """module org.bar.Module2
        |
        |import "../../org/foo/Module1.pkl"
        |
        |class Group {
        |  owner: Module1.Person
        |  name: String
        |}
        |""".stripMargin
    )
    val outDir = dir.resolve("out")
    CliScalaCodeGenerator(
      CliScalaCodeGeneratorOptions(
        List(mod1.toUri, mod2.toUri),
        outDir,
        renames = Map(
          "org.foo" -> "com.foo.x",
          "org.bar.Module2" -> "org.bar.RenamedModule"
        )
      )
    ).run()
    val mod1File = outDir.resolve("scala/com/foo/x/Module1.scala")
    assert(Files.exists(mod1File), s"missing $mod1File")
    assert(Files.readString(mod1File).contains("package com.foo.x"))
    val mod2File = outDir.resolve("scala/org/bar/RenamedModule.scala")
    assert(Files.exists(mod2File), s"missing $mod2File")
    val mod2Content = Files.readString(mod2File)
    assert(mod2Content.contains("package org.bar"), mod2Content)
    assert(mod2Content.contains("com.foo.x.Module1"), mod2Content)
  }
