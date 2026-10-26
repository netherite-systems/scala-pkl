package org.pkl.codegen.scala

import java.net.URLClassLoader
import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Compiles generated Scala sources with the real Scala 3 compiler.
  *
  * Port of Kotlin's `InMemoryKotlinCompiler` (which uses the `kts` script
  * engine). Scala has no script-engine equivalent, so sources are written to a
  * temp dir and compiled with `dotty.tools.dotc` using the current test
  * classpath (pkl-core, stdlib, ...).
  */
object InMemoryScalaCompiler:
  final class CompilationFailedException(msg: String, cause: Throwable = null)
      extends RuntimeException(msg, cause)

  /** Compiled output: classloader plus classes keyed by simple name.
    *
    * Simple names are unique within a single test (one package per compile,
    * same assumption as the Kotlin helper). Use `loader.loadClass(fqn)` for
    * multi-package compiles.
    */
  final class CompiledClasses(
      val loader: URLClassLoader,
      val classes: Map[String, Class[?]],
      val bySimpleName: Map[String, Class[?]]
  ):
    def getValue(simpleOrFqn: String): Class[?] =
      bySimpleName.getOrElse(
        simpleOrFqn,
        classes
          .filter((fqn, _) =>
            fqn == simpleOrFqn || fqn.endsWith("." + simpleOrFqn) || fqn
              .endsWith(
                "$" + simpleOrFqn
              )
          )
          .values
          .toList match
          case List(single) => single
          case Nil          => loader.loadClass(simpleOrFqn)
          case many         =>
            throw new NoSuchElementException(
              s"ambiguous class name `$simpleOrFqn`: ${many.map(_.getName).mkString(", ")}"
            )
      )

  def compile(sources: Map[String, String]): CompiledClasses =
    val srcDir = Files.createTempDirectory("pkl-scala-compile-src")
    val outDir = Files.createTempDirectory("pkl-scala-compile-out")
    val scalaFiles = sources.toList.collect {
      case (name, content) if name.endsWith(".scala") =>
        // generated names look like "scala/my/Mod.scala"; dotc only needs the
        // package-relative path.
        val rel = if name.startsWith("scala/") then name.stripPrefix("scala/")
        else name
        val file = srcDir.resolve(rel)
        Files.createDirectories(file.getParent)
        Files.writeString(file, content)
        file.toString
    }
    require(
      scalaFiles.nonEmpty,
      s"no .scala sources in ${sources.keys.mkString(",")}"
    )
    // NOTE: do NOT run dotc in-process via `new Driver().process(...)`.
    // Under sbt the compiler classes are loaded by sbt's LayeredClassLoader and
    // in-process runs fail with `NoSymbol cannot be cast to ClassSymbol`.
    // Fork a fresh JVM with dotty.tools.dotc.Main instead, using the test
    // classpath from the context classloader (sbt's forked worker keeps only
    // its own jars on `java.class.path`).
    val classpath =
      Thread.currentThread.getContextClassLoader match
        case u: URLClassLoader =>
          u.getURLs.toList
            .map(u => java.nio.file.Paths.get(u.toURI).toString)
            .mkString(":")
        case _ =>
          Option(System.getProperty("test.classpath"))
            .filter(_.nonEmpty)
            .getOrElse(System.getProperty("java.class.path"))
    val javaBin = System.getProperty("java.home") + "/bin/java"
    val cmd = Seq(
      javaBin,
      "-cp",
      classpath,
      "dotty.tools.dotc.Main",
      "-classpath",
      classpath,
      "-d",
      outDir.toString
    ) ++ scalaFiles
    val output = new StringBuilder
    val logger = scala.sys.process.ProcessLogger(
      (o: String) => output.append(o).append("\n"),
      (e: String) => output.append(e).append("\n")
    )
    val exit = scala.sys.process.Process(cmd).!(logger)
    if exit != 0 then
      throw CompilationFailedException(
        s"Scala compilation failed (exit=$exit) for ${sources.keys.mkString(",")}:\n$output"
      )
    val loader = new URLClassLoader(
      Array(outDir.toUri.toURL),
      getClass.getClassLoader
    )
    val classes = Files
      .walk(outDir)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".class"))
      // skip anonymous / local classes; keep `Foo`, `Foo$`, `BirdKind$PIGEON`, ...
      .filterNot(p => p.getFileName.toString.contains("$anon"))
      .map { p =>
        val rel = outDir.relativize(p).toString
        val fqn = rel.stripSuffix(".class").replace('/', '.').replace('\\', '.')
        try Some(fqn -> loader.loadClass(fqn))
        catch case _: NoClassDefFoundError | _: ClassNotFoundException => None
      }
      .collect { case Some(e) => e }
      .toMap
    // Index by terminal simple name: `my.Birds$Person` -> `Person`,
    // `my.Birds` -> `Birds`. Generated types usually live inside
    // `object Mod`, so the JVM name carries a `$` infix. A trailing `$`
    // marks a companion object; prefer the class itself on collisions.
    val bySimple = classes.toList
      .map((fqn, cls) =>
        val base = if fqn.endsWith("$") then fqn.stripSuffix("$") else fqn
        base.split('.').last.split('$').filter(_.nonEmpty).last -> (fqn, cls)
      )
      .groupBy(_._1)
      .flatMap {
        case (simple, List((_, (_, cls)))) => Some(simple -> cls)
        case (simple, entries)             =>
          entries
            .collectFirst {
              case (_, (fqn, cls)) if !fqn.endsWith("$") => simple -> cls
            }
            .orElse(entries.headOption.map { case (s, (_, cls)) => s -> cls })
      }
    CompiledClasses(loader, classes, bySimple)
