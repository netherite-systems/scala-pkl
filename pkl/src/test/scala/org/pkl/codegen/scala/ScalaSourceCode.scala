package org.pkl.codegen.scala

import munit.Assertions._

/** Single-file view of generator output, mirroring Kotlin's `KotlinSourceCode`
  * + `KotlinSourceCodeAssert`.
  */
final case class ScalaSourceCode(text: String):
  def compile(): InMemoryScalaCompiler.CompiledClasses =
    InMemoryScalaCompiler.compile(Map("Mod.scala" -> text))

  def deleteLines(predicate: String => Boolean): ScalaSourceCode =
    ScalaSourceCode(text.linesIterator.filterNot(predicate).mkString("\n"))

object ScalaSourceCode:
  def assertContains(src: String, part: String): Unit =
    assert(
      src.contains(part),
      s"missing `$part` in:\n$src"
    )

  def assertNotContains(src: String, part: String): Unit =
    assert(
      !src.contains(part),
      s"unexpected `$part` in:\n$src"
    )

  def assertCompilesSuccessfully(
      src: String
  ): InMemoryScalaCompiler.CompiledClasses =
    try InMemoryScalaCompiler.compile(Map("Mod.scala" -> src))
    catch
      case e: InMemoryScalaCompiler.CompilationFailedException =>
        fail(
          s"generated code did not compile: ${e.getMessage}\n--- source ---\n$src"
        )

  def assertCompilesSuccessfully(
      files: Map[String, String]
  ): InMemoryScalaCompiler.CompiledClasses =
    try InMemoryScalaCompiler.compile(files)
    catch
      case e: InMemoryScalaCompiler.CompilationFailedException =>
        val dump = files.map((n, c) => s"--- $n ---\n$c").mkString("\n")
        fail(s"generated code did not compile: ${e.getMessage}\n$dump")

  def assertEqualToResourceFile(src: String, resourceName: String): Unit =
    val stream = classOf[ScalaSourceCode].getResourceAsStream(
      s"/org/pkl/codegen/scala/$resourceName"
    )
    assert(stream != null, s"missing test resource $resourceName")
    val expected =
      try new String(stream.readAllBytes(), "UTF-8")
      finally stream.close()
    assertEquals(src, expected)
