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
