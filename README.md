# pkl-codegen-scala

Scala 3 code generator for [Pkl](https://pkl-lang.org) modules — a port of
[`pkl-codegen-kotlin`](https://github.com/apple/pkl/tree/main/pkl-codegen-kotlin) (apple/pkl)
to idiomatic Scala 3. Core generator library + CLI only; sbt/mill plugin glue is intentionally
left out (see *Plugin seam*).

Built with **sbt 2.x**, formatted with **scalafmt**, linted with **scalafix**, and cross-built for
**JVM, Scala.js and Scala Native**.

## Layout

```
core/shared/src/main/scala/org/pkl/codegen/scala/
  ScalaRenderer.scala           # pure IR -> Scala 3 source (cross: JVM/JS/Native)
  ScalaCodeGeneratorOptions.scala
  ScalaCodeGeneratorException.scala
  ir/IrModel.scala              # portability boundary: pure schema description
  internal/
    Emitter.scala               # plain-string emitter (indent, imports, backticks, Scaladoc)
    NameMapper.scala            # Pkl module name => (package, class); port of pkl-commons
core/shared/src/test/scala/...  # evaluator-free tests, run on ALL platforms
pkl/src/main/scala/org/pkl/codegen/scala/
  ScalaCodeGenerator.scala      # facade: ModuleSchema => IR => source + resources (JVM-only)
  PklSchemaAdapter.scala        # pkl-core ModuleSchema => IR (JVM-only)
  CliScalaCodeGenerator.scala   # evaluateSchema => generate => write under outputDir
  CliScalaCodeGeneratorOptions.scala
  Main.scala                    # CLI entry point
pkl/src/test/scala/...          # evaluator-based tests (JVM-only)
```

`pkl-core` (Truffle-based) can only run on the JVM, so cross-compilation works through a split:
the JVM-only `PklSchemaAdapter` translates `ModuleSchema` into a pure `IrModule`, which the
cross-built `ScalaRenderer` renders. Anything that can construct the IR — e.g. a future native
evaluator — can reuse the renderer on JS/Native today.

## Build

Requires Java 17+ and sbt 2.x (bootstrapped via `project/build.properties`; no manual install).
Runtime dependency: `org.pkl-lang:pkl-core:0.31.1` (schema API only; `NameMapper` is vendored
because `pkl-commons` is not published for this version). Project Scala: 3.3.x LTS.

```bash
sbt "coreJVM/test; pkl/test"          # JVM suites (10 core + 21 end-to-end)
sbt pklSbt/scripted                    # sbt plugin: generate + file-exists + compile, in a real consumer build
sbt coreJS/Test/compile                # Scala.js: sources + tests compile, fastLinkJS links
sbt coreJS/test                        # needs a real Node.js (see below)
sbt coreNative/test                    # links via clang, runs on a real binary (10 tests)
sbt scalafmtAll scalafmtCheckAll       # format / verify formatting (scalafmt 3.11.4)
sbt scalafixAll                        # OrganizeImports + RemoveUnused (scalafix 0.14.9)
sbt "pkl/run --output-dir out <module>..."
```

sbt 2 notes: separate multiple commands with `;` inside one `-batch` string; on sbt 2.x, plain
`%%` in cross-project settings is platform-aware (`%%%` is sbt 1.x only). sbt 2 also runs `test`
incrementally — unchanged suites report `Total 0 / No tests to run`, which is expected; use
`testOnly <glob>` (e.g. `coreNative/testOnly org.pkl.codegen.scala.*`) to force execution.
`coreNative/test` is pinned to that explicit form in `build.sbt` so the cross matrix can never
pass vacuously.

Environment caveats: `coreJS/test` executes against the `node` on `PATH` — a Bun-based `node`
shim cannot run the Scala.js test RPC, so that step needs real Node.js; compile and link are
still verified without it. `coreNative/test` forces explicit execution (plain `test` delegates
to `testQuick`, whose change detection misfires with the external-process runner and can report
vacuous 0-test passes).

## Output

Per module, `output` returns:

- `scala/<pkg-path>/<Class>.scala` — the module wrapper (`object Mod`, or the module
  class plus an `object Mod` companion when the module has properties) with nested classes;
  enums and type aliases stay top-level,
- `resources/META-INF/org/pkl/config/scala/mapper/classes/<module>.properties`
  (`org.pkl.config.scala.mapper.<pklQN>=<jvmFQN>`, for a future `pkl-config-scala` runtime),
- `resources/META-INF/org/pkl/config/java/mapper/classes/<module>.properties` — same mappings
  under the `org.pkl.config.java.mapper.` prefix, so generated classes work with the existing
  `pkl-config-java` mapper today (they are plain JVM classes).

## Pkl → Scala 3 mapping

| Pkl | Scala |
|---|---|
| leaf class | `case class` (free `copy`/`equals`/`hashCode`/`toString`) |
| `open` class | `open class` + hand-rolled `copy`/`equals`/`hashCode`/`toString` |
| `abstract` class | `abstract class`, no `copy` |
| module without properties | `object Mod` |
| string-literal union | `enum X(val value: String)` + `toString = value` |
| other typealias | top-level `type X[A] = ...` (variance `+A`/`-A` preserved) |
| `Int` / `Float` / `Number` | `Long` / `Double` / `Double` |
| `Int8` / `Int16`·`UInt8` / `Int32`·`UInt16` / `UInt`·`UInt32` | `Byte` / `Short` / `Int` / `Long` |
| nullable `T?` | `Option[T]` |
| `Listing`/`List` / `Mapping`/`Map` / `Set` / `Collection` / `Pair` | `List` / `Map` / `Set` / `Iterable` / `(A, B)` tuple |
| `Bytes` / `Regex` / `Uri` / `Duration`(+Unit) / `DataSize`(+Unit) / `Version` | `Array[Byte]` / `scala.util.matching.Regex` / `java.net.URI` / `org.pkl.core.*` |
| `hidden` properties | omitted |
| `@Deprecated` / doc comments | `@deprecated` / Scaladoc (`--generate-scaladoc`) |

`Regex`/`Array[Byte]` properties get structural `equals`/`hashCode` (`pattern` / `contentEquals`);
`Array[Byte]` additionally gets an `Arrays.toString` `toString`.

## Divergences from pkl-codegen-kotlin (deliberate)

1. **Nesting via companion.** Like Kotlin, Pkl classes nest inside the `object Mod`
   wrapper — or the module class's companion when the module has properties (nesting them
   inside a `case class` would make them path-dependent inner classes). Nested references
   always render qualified (`Mod.Person`): the companion class cannot see the companion
   object's members unqualified, while a self-qualified reference resolves in every scope.
   JVM names match Kotlin's shape (`my.Mod$Person`). Enums and type aliases stay top-level.
2. **`Option[T]`** instead of nullable references.
3. **No Spring Boot support** (no Scala equivalent); the option is omitted, not stubbed.
4. **`@Generated`** maps to `javax.annotation.processing.Generated` (JDK built-in) since there is no
   `pkl-config-scala` artifact yet.
5. **Copy defaults only on leaves.** Scala rejects two overloads that both define default arguments
   (inherited members count). The main `copy` gets `= this.x` defaults only when the class has no
   subclasses in the module and no external superclass; ancestor-arity `override def copy` variants
   never have defaults. Same rule applies across modules: a class extending an external class never
   puts defaults on its main copy.
6. **Plain-string emitter**, no KotlinPoet equivalent.

## Plugin seam

```scala
val schema = evaluator.evaluateSchema(ModuleSource.uri(uri)) // reuse one Evaluator per build
val files: Map[String, String] =
  ScalaCodeGenerator(schema, ScalaCodeGeneratorOptions(renames = Map("org.foo" -> "com.foo"))).output
files.foreach { (name, contents) =>
  val out = outputDir.resolve(name) // paths already split into scala/ + resources/
  Files.createDirectories(out.getParent); Files.writeString(out, contents)
}
```

`CliScalaCodeGenerator` is the reference implementation of that loop. `pkl-config-scala`
(native `Option`/enum decoding via `Mirror`) is a separate, future module.

## Build plugins

Like `pkl-gradle` in the Pkl repo (same shape: spec DSL → one cached task → in-process
generator call → source-set wiring), this repo ships `pkl-sbt` (sbt 2.x `AutoPlugin`) and
`pkl-mill` (Mill 1.x trait).

sbt (`pkl-sbt`, verified end-to-end by a scripted test that generates and compiles):

```scala
// project/plugins.sbt
addSbtPlugin("org.pkl-lang" % "pkl-sbt" % version)
// build.sbt
lazy val app = project.enablePlugins(PklPlugin).settings(
  pklGenerators += PklGeneratorSpec(
    sourceModules = Seq(file("src/main/pkl/birds.pkl")),
    generateScaladoc = true,
    renames = Map("org.foo" -> "com.foo")
  )
)
```

`pklGenerate` runs all specs (cached on input contents; options feed the cache key),
`Compile / sourceGenerators` / `resourceGenerators` pick up the outputs, so `compile` just
works. sbt 2 caches every task result, so file outputs travel as rel-path strings internally.

Mill (`pkl-mill`, compiles against mill-libs 1.3.1; run it under mill 1.3.x):

```scala
// build.sc
import $ivy.`org.pkl-lang::mill-pkl:0.1.0`
import org.pkl.mill.PklScalaModule

object app extends ScalaModule with PklScalaModule {
  def scalaVersion = "3.3.8"
  override def pklSources = Task.Sources(moduleDir / "somewhere-else")
  override def pklGenerateScaladoc = Task { true }
}
```

`pklGenerate` evaluates once and `generatedSources`/`resources` split the pair; mill's
content-hashed tasks make regeneration (the Gradle `GatherImports` equivalent) automatic.
Per-field `T` settings (`pklIndent`, `pklImplementSerializable`, …) keep caching granular.
