# Architecture

`mybatis-lint` is a monorepo with two cooperating pieces:

```
mybatis-lint/
├─ analyzer/            Java 17 Gradle project - the analysis engine + CLI
├─ vscode-extension/     TypeScript VS Code extension - a thin client
├─ fixtures/             Sample mapper XML used by both tests and manual runs
├─ docs/                 This file, the roadmap, the diagnostics schema, rule details
└─ .github/workflows/    CI and platform-specific VSIX packaging
```

## Why a CLI instead of an LSP?

The project deliberately avoids building a Language Server. A
JSON-emitting CLI is much faster to build, is trivially testable with plain
JUnit, and can already be reused from a terminal, a CI pipeline, or a
pre-commit hook — not just from VS Code. If/when incremental,
as-you-type diagnostics are needed, the analyzer's internals (discovery,
parsing, rules) can be wrapped by an LSP server without being rewritten.

## `analyzer/` (Java 17, single Gradle module)

Kept as **one** Gradle module for now. Packages are already split along the
lines a future split would follow, so extracting modules later (e.g. a
`sql-parser` module) is mostly a matter of moving packages:

- `discovery` — walks the workspace for candidate mapper XML files
  (`MapperFileScanner`) and Java sources (`JavaFileScanner`), skipping
  `.git`, `.gradle`, `build`, `target`, `node_modules`, paths given with
  `--exclude` (`ExcludePatterns`), and anything that cannot be read (for
  example Docker volumes owned by another user).
- `javaindex` — parses the Java sources with JavaParser (the only runtime
  dependency). `JavaSources` parses every file once, in parallel, and both
  indexes are built from that result. `JavaMapperIndex` records Mapper interface methods (parameter
  names, `@Param` values, parameter and return types); `JavaTypeIndex`
  records classes and their fields/setters. Nothing is compiled or resolved
  against a classpath, so ambiguous or unresolved types are skipped.
- `xml` — the rule checks. `MapperXmlAnalyzer` parses each file with SAX
  (`SafeXmlParserFactory`) for the basic structural rules, then runs the
  DOM-based validators:
  - `MapperReferenceValidator` — `<include>`, resultMap references,
    inheritance, duplicate IDs, typeAlias candidates, resultMap properties
  - `MapperParameterValidator` — undefined parameter references
  - `MapperParameterTypeValidator` — `parameterType` vs. Java argument type
  - `MapperResultTypeValidator` — `resultType` vs. Java return type

  `MapperSqlFragmentIndex` and `MapperResultMapIndex` are built once over all
  mapper files, so references across files can be resolved. Every validator
  reads XML through `XmlSources`; during one CLI run it caches each file's
  text and DOM, so a file is parsed once instead of once per validator.
- `model` — `Diagnostic`, `Range`, `Position`, `Severity`, and the canonical
  list of rule IDs (`RuleIds`).
- `json` — a small hand-written JSON writer (see below for why).
- `cli` — `AnalyzerCli` builds the indexes, analyzes every mapper file, and
  writes the JSON report; `Main` is the thin `public static void main` entry
  point. `DiagnosticFilter` runs last and drops diagnostics for rules turned
  off with `--disable` and lines or files silenced with
  `mybatis-lint-disable-next-line` / `mybatis-lint-disable-file` comments, so
  every rule is silenced the same way.

### Why no JSON library dependency?

Runtime dependencies are kept to a minimum (currently JavaParser only). A
JSON library would be used purely to serialize a small, fixed-shape list of
diagnostics, so a short hand-written writer (`JsonWriter`) is used instead.
This keeps the jar small and avoids adding license/dependency notes for a
task this simple. This is deliberately not a general-purpose JSON library;
if the output shape grows significantly more complex, revisit this decision.

### Extension points for future work

- **SQL parsing** (Oracle syntax validation): not implemented yet.
  The plan is to add a `sqlparser` package with a small `SqlParser`
  interface, and an adapter around a third-party Oracle-capable parser
  (candidates: Druid's SQL parser, or JSqlParser). See `roadmap.md`.
- **Dynamic SQL branch expansion** (`<if>`/`<choose>` producing invalid SQL
  only in some branches): not implemented in this PR; requires both of the
  above extension points to be useful, since expanding branches without
  parsing the resulting SQL doesn't help.

## `vscode-extension/` (TypeScript, strict mode)

- `src/extension.ts` — the only file that imports `vscode`. Registers the
  `mybatisLint.checkWorkspace` command, resolves settings, spawns the
  analyzer, and publishes results to a `vscode.DiagnosticCollection`.
- `src/analyzerRunner.ts` — spawns
  `java -XX:TieredStopAtLevel=1 -jar <jar> [--exclude <glob>]... [--disable <rule>]... <workspace>`
  (a run lasts a second or two, too short for the C2 JIT to pay off)
  (one option per entry of the `mybatisLint.exclude` and
  `mybatisLint.disabledRules` settings) and collects
  stdout; a non-zero exit code is *expected* whenever diagnostics exist, so
  it is not itself treated as failure.
- `src/parseAnalyzerOutput.ts` — parses/validates the analyzer's JSON.
- `src/diagnosticsMapper.ts` — pure conversion from the analyzer's 1-based
  JSON schema to 0-based, VS-Code-shaped diagnostics, grouped by file URI.
- `src/jarPathResolver.ts` — resolves the analyzer jar path from the
  `mybatisLint.analyzerJarPath` setting, falling back to the bundled jar and
  then to `analyzer/build/libs/analyzer.jar` during development.
- `src/javaPathResolver.ts` — resolves the Java executable: the
  `mybatisLint.javaPath` setting, then the bundled `jre/`, then `JAVA_HOME`,
  SDKMAN, and finally `java` on the PATH.
- `src/quickFixes.ts` — remembers each diagnostic's `suggestion` so that
  `extension.ts` can offer it as a "Change to '...'" quick fix.
- `src/summaryMessage.ts` — builds the notification shown after a check,
  including the "no mapper XML found" case.
- `src/checkScheduler.ts` — decides when save-triggered checks run: saves are
  debounced, and a save during a run queues a single follow-up run instead
  of a concurrent one.

`extension.ts` also writes each run (paths used, analyzer stderr such as
skipped unreadable folders, duration, summary) to the "MyBatis Lint" output
channel. The analyzer keeps stdout for JSON only, so these notes travel on
stderr.

Everything except `extension.ts` is free of any `vscode` import, so it can
be unit tested with the Node.js built-in test runner (`node --test`)
without spinning up a real Extension Host. This keeps the test suite fast
while still covering the logic that is easy to get wrong (position
conversion, JSON validation, argument building, error messages).

## Data contract between the two halves

The analyzer's JSON output is the only contract between the CLI and the
extension (or any other consumer). It is documented in full in
[`diagnostics-schema.md`](./diagnostics-schema.md).
