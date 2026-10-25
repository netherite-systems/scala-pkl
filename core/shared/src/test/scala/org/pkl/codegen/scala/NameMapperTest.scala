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
import org.pkl.codegen.scala.internal.NameMapper

class NameMapperTest extends FunSuite:
  test("default mapping capitalizes class name") {
    assertEquals(NameMapper(Map.empty).map("my.mod"), ("my", "Mod"))
    assertEquals(NameMapper(Map.empty).map("birds"), ("", "Birds"))
  }

  test("longest prefix wins") {
    val mapper =
      NameMapper(Map("my." -> "com.example.", "my.sub." -> "org.other."))
    assertEquals(mapper.map("my.mod"), ("com.example", "Mod"))
    assertEquals(mapper.map("my.sub.mod"), ("org.other", "Mod"))
  }

  test("explicit class rename is kept as-is") {
    val mapper = NameMapper(Map("my.mod" -> "com.example.Custom"))
    assertEquals(mapper.map("my.mod"), ("com.example", "Custom"))
  }
