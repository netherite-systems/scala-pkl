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

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/** Main method for the Scala code generator CLI.
  *
  * Usage: `pkl-codegen-scala [options] <module>...`
  */
object Main:
  def main(args: Array[String]): Unit =
    parse(args) match
      case Left(error) =>
        Console.err.println(error)
        Console.err.println(usage)
        sys.exit(2)
      case Right(options) =>
        try CliScalaCodeGenerator(options).run()
        catch
          case e: CliException =>
            Console.err.println(s"pkl-codegen-scala: ${e.getMessage}")
            sys.exit(1)

  private val usage: String =
    """Usage: pkl-codegen-scala [options] <module>...
      |Options:
      |  -o, --output-dir <path>     Directory for generated code (default: .)
      |  --indent <chars>             Indentation (default: two spaces)
      |  --generate-scaladoc          Preserve Pkl doc comments as Scaladoc
      |  --implement-serializable     Generated classes extend Serializable
      |  --add-generated-annotation   Add @Generated to generated types
      |  --rename <old=new>           Rename a Pkl module prefix (repeatable)
      |""".stripMargin

  // Visible for testing.
  def parse(args: Array[String]): Either[String, CliScalaCodeGeneratorOptions] =
    var outputDir: Path = Paths.get(".")
    var indent = "  "
    var generateScaladoc = false
    var implementSerializable = false
    var addGeneratedAnnotation = false
    val renames = Map.newBuilder[String, String]
    val modules = List.newBuilder[URI]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "-o" | "--output-dir" =>
          if !it.hasNext then return Left("Missing value for --output-dir.")
          outputDir = Paths.get(it.next())
        case "--indent" =>
          if !it.hasNext then return Left("Missing value for --indent.")
          indent = it.next()
        case "--generate-scaladoc"        => generateScaladoc = true
        case "--implement-serializable"   => implementSerializable = true
        case "--add-generated-annotation" => addGeneratedAnnotation = true
        case "--rename"                   =>
          if !it.hasNext then return Left("Missing value for --rename.")
          it.next().split("=", 2) match
            case Array(oldName, newName) => renames += (oldName -> newName)
            case _ => return Left("Invalid --rename value; expected old=new.")
        case "-h" | "--help"                    => return Left("")
        case unknown if unknown.startsWith("-") =>
          return Left(s"Unknown option: $unknown")
        case module =>
          try modules += toUri(module)
          catch
            case _: IllegalArgumentException =>
              return Left(s"Invalid module URI: $module")
    val moduleList = modules.result()
    if moduleList.isEmpty then Left("No modules given.")
    else
      Right(
        CliScalaCodeGeneratorOptions(
          moduleList,
          outputDir,
          indent,
          generateScaladoc,
          implementSerializable,
          addGeneratedAnnotation,
          renames.result()
        )
      )

  private def toUri(module: String): URI =
    val uri = URI.create(module)
    if uri.getScheme == null then Paths.get(module).toAbsolutePath.toUri
    else uri
