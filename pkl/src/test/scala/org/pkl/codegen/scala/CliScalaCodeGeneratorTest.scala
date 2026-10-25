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

import java.nio.file.Files

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
