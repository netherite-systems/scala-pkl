# scala-pkl

Scala 3 code generator for [Pkl](https://pkl-lang.org) modules — a port of
[`pkl-codegen-kotlin`](https://github.com/apple/pkl/tree/main/pkl-codegen-kotlin) to idiomatic
Scala 3. Cross-built for JVM, Scala.js and Scala Native; sbt 2.x build with scalafmt/scalafix.

## Modules

- `core/` — pure IR + renderer, no pkl-core dependency (JVM/JS/Native)
- `pkl/` — pkl-core adapter + CLI; `ScalaCodeGenerator(...).output` is the plugin seam
- `pkl-sbt/` — sbt 2.x `AutoPlugin` (`pklGenerators` → `sourceGenerators`)
- `mill-pkl/` — Mill 1.x `PklScalaModule` trait (`generatedSources`/`resources`)

`pkl-core` only runs on the JVM, so the adapter translates `ModuleSchema` into a pure `IrModule`
that the cross-built renderer consumes.

## Build

Java 17+, sbt 2.x (`project/build.properties` bootstraps it), Scala 3.3.x, pkl-core 0.31.1.

```bash
sbt "coreJVM/test; pkl/test"   # JVM suites
sbt coreNative/test            # real binary (forced explicit; plain test is incremental)
sbt pklSbt/scripted            # plugin end-to-end in a consumer build
sbt coreJS/Test/fastLinkJS     # JS link (tests need real Node.js)
sbt scalafmtAll scalafixAll
sbt "pkl/run --output-dir out <module>..."
```

sbt 2 runs `test` incrementally (`Total 0` when nothing changed is expected); `testOnly <glob>`
forces execution. Separate commands with `;` inside one `-batch` string.

## sbt

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

## Mill

```scala
// build.sc
import $ivy.`org.pkl-lang::mill-pkl:0.1.0`
import org.pkl.mill.PklScalaModule

object app extends ScalaModule with PklScalaModule {
  def scalaVersion = "3.3.8"
  override def pklGenerateScaladoc = Task { true }
}
```

## Notes

- Nullable `T?` → `Option[T]`; `Pair` → tuple; `Listing`/`Mapping` → `List`/`Map`. No Spring support.
- Nested classes live in `object Mod` (or the module class companion); refs render qualified
  (`Mod.Person`). Enums and type aliases stay top-level.
- Copy defaults only on leaf classes (Scala forbids two defaulted overloads in one scope).
- `output` returns `scala/...` + `resources/...` (Scala- and Java-mapper `.properties`).

## Attribution

Generator logic, naming and option shapes ported from https://github.com/apple/pkl
(`pkl-codegen-kotlin`, `pkl-commons`, `pkl-gradle`; Apache License 2.0). `NameMapper` is a direct
port of `org.pkl.commons.NameMapper`; test fixtures mirror `KotlinCodeGeneratorTest`.
