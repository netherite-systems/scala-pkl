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

import org.pkl.codegen.scala.internal.NameMapper
import org.pkl.core._
import org.pkl.core.util.IoUtils

import java.io.StringWriter
import java.util.Properties
import scala.jdk.CollectionConverters._

/** Entrypoint for the Scala 3 code generator API.
  *
  * Pure function: `ModuleSchema + options => Map[fileName -> contents]`. Plugin
  * seam: sbt/mill plugins should call [[output]] in-process and write the map
  * (see [[CliScalaCodeGenerator]] for the file-writing loop). This is a port of
  * `KotlinCodeGenerator` from `pkl-codegen-kotlin` with three deliberate
  * divergences:
  *
  *   - all types are emitted as **top-level** declarations (no `object Mod`
  *     wrapper); nested Pkl classes become top-level `case` classes in the same
  *     package,
  *   - Pkl nullable types map to **`Option[T]`** instead of nullable
  *     references,
  *   - code is emitted with a plain string builder instead of a codegen AST
  *     library.
  *
  * Implementation: the JVM-only [[PklSchemaAdapter]] translates the schema into
  * a pure IR, which the cross-built [[ScalaRenderer]] renders. Only this facade
  * (plus file names and mapper resources below) touches pkl-core.
  */
final class ScalaCodeGenerator(
    moduleSchema: ModuleSchema,
    options: ScalaCodeGeneratorOptions
):
  private val nameMapper = NameMapper(options.renames)

  private val ScalaPropertyPrefix = "org.pkl.config.scala.mapper."
  private val JavaPropertyPrefix = "org.pkl.config.java.mapper."

  /** Generated files: one `.scala` source plus mapper `.properties` resources.
    *
    * Both a Scala-mapper and a Java-mapper properties file are written (same
    * JVM mappings, different prefixes) so generated classes work with a future
    * `pkl-config-scala` runtime as well as the existing `pkl-config-java`
    * mapper today.
    */
  def output: Map[String, String] = Map(
    scalaFileName -> scalaFile,
    scalaPropertyFileName -> propertiesFile(ScalaPropertyPrefix, "Scala"),
    javaPropertyFileName -> propertiesFile(JavaPropertyPrefix, "Java")
  )

  private def scalaPropertyFileName: String =
    s"resources/META-INF/org/pkl/config/scala/mapper/classes/${IoUtils.encodePath(moduleSchema.getModuleName)}.properties"

  private def javaPropertyFileName: String =
    s"resources/META-INF/org/pkl/config/java/mapper/classes/${IoUtils.encodePath(moduleSchema.getModuleName)}.properties"

  private def propertiesFile(prefix: String, label: String): String =
    val props = new Properties()
    val moduleClass = moduleSchema.getModuleClass
    props.setProperty(
      prefix + moduleClass.getQualifiedName,
      jvmName(moduleClass)
    )
    for pClass <- moduleSchema.getClasses.values.asScala do
      props.setProperty(prefix + pClass.getQualifiedName, jvmName(pClass))
    val writer = new StringWriter()
    props.store(
      writer,
      s"$label mappings for Pkl module `${moduleSchema.getModuleName}`"
    )
    writer.toString

  private def scalaFileName: String =
    val (packageName, className) = nameMapper.map(moduleSchema.getModuleName)
    val dirPath = packageName
      .split('.')
      .toList
      .filter(_.nonEmpty)
      .map(IoUtils.encodePath)
      .mkString("/")
    val sb = new StringBuilder("scala/")
    if dirPath.nonEmpty then sb.append(dirPath).append("/")
    sb.append(IoUtils.encodePath(className)).append(".scala")
    sb.result()

  /** Flat JVM name: every generated type is top-level, so no `$` nesting. */
  private def jvmName(pClass: PClass): String =
    val (packageName, _) = nameMapper.map(pClass.getModuleName)
    val simple =
      if pClass.isModuleClass then nameMapper.map(pClass.getModuleName)._2
      else pClass.getSimpleName
    if packageName.isEmpty then simple else s"$packageName.$simple"

  def scalaFile: String =
    if moduleSchema.getModuleUri.getScheme == "pkl" then
      throw ScalaCodeGeneratorException(
        s"Cannot generate Scala code for a Pkl standard library module (`${moduleSchema.getModuleUri}`)."
      )
    ScalaRenderer(options).renderModule(
      PklSchemaAdapter.adapt(moduleSchema, options)
    )
