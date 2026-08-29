# Architecture

`mybatis-lint` is a monorepo with two cooperating pieces:

```
mybatis-lint/
├─ analyzer/            Java 17 Gradle project - the analysis engine + CLI
├─ vscode-extension/     TypeScript VS Code extension - a thin client
├─ fixtures/             Sample mapper XML used by both tests and manual runs
├─ docs/                 This file, the roadmap, and the diagnostics schema
└─ .github/workflows/    CI
```

## Why a CLI instead of an LSP?

The initial slice deliberately avoids building a Language Server. A
JSON-emitting CLI is much faster to build, is trivially testable with plain
JUnit, and can already be reused from a terminal, a CI pipeline, or a
pre-commit hook — not just from VS Code. If/when incremental,
as-you-type diagnostics are needed, the analyzer's internals (discovery,
parsing, rules) can be wrapped by an LSP server without being rewritten.

## `analyzer/` (Java 17, single Gradle module)

Kept as **one** Gradle module for now. Packages are already split along the
lines a future split would follow, so extracting modules later (e.g. a
`sql-parser` module) is mostly a matter of moving packages:

- `discovery` — walks the workspace for candidate mapper XML files, skipping
  `.git`, `.gradle`, `build`, `target`, `node_modules`.
- `xml` — safe SAX-based parsing (`SafeXmlParserFactory`) plus the actual
  rule checks (`MapperXmlAnalyzer`). Rules are currently implemented inline
  in the SAX handler rather than as a separate `Rule` abstraction, since
  there are only a handful of them; if the rule set grows, extracting a
  `Rule` interface with one implementation per rule is the natural next step.
- `model` — `Diagnostic`, `Range`, `Position`, `Severity`, and the canonical
  list of rule IDs (`RuleIds`).
- `json` — a small hand-written JSON writer (see below for why).
- `cli` — `AnalyzerCli` ties discovery + analysis together; `Main` is the
  thin `public static void main` entry point.

### Why no JSON library dependency?

The analyzer's only third-party runtime dependency would otherwise be a JSON
library, purely to serialize a small, fixed-shape list of diagnostics. A
~70-line hand-written writer (`JsonWriter`) avoids that dependency entirely,
which keeps the jar small and avoids adding license/dependency notes for a
task this simple. This is deliberately not a general-purpose JSON library;
if the output shape grows significantly more complex, revisit this decision.

### Extension points for future work

- **SQL parsing** (Oracle syntax validation): not implemented in this PR.
  The plan is to add a `sqlparser` package with a small `SqlParser`
  interface, and an adapter around a third-party Oracle-capable parser
  (candidates: Druid's SQL parser, or JSqlParser). See `roadmap.md`.
- **Java symbol analysis** (Mapper interface parameter resolution,
  `resultType` property validation): not implemented in this PR. The plan is
  a `javasymbols` package using the JDK's own `javax.lang.model`/compiler
  APIs (or a small subset of the Eclipse JDT parser) to resolve interface
  method signatures without a full build.
- **Dynamic SQL branch expansion** (`<if>`/`<choose>` producing invalid SQL
  only in some branches): not implemented in this PR; requires both of the
  above extension points to be useful, since expanding branches without
  parsing the resulting SQL doesn't help.

## `vscode-extension/` (TypeScript, strict mode)

- `src/extension.ts` — the only file that imports `vscode`. Registers the
  `mybatisLint.checkWorkspace` command, resolves settings, spawns the
  analyzer, and publishes results to a `vscode.DiagnosticCollection`.
- `src/analyzerRunner.ts` — spawns `java -jar <jar> <workspace>` and collects
  stdout; a non-zero exit code is *expected* whenever diagnostics exist, so
  it is not itself treated as failure.
- `src/parseAnalyzerOutput.ts` — parses/validates the analyzer's JSON.
- `src/diagnosticsMapper.ts` — pure conversion from the analyzer's 1-based
  JSON schema to 0-based, VS-Code-shaped diagnostics, grouped by file URI.
- `src/jarPathResolver.ts` — resolves the analyzer jar path from the
  `mybatisLint.analyzerJarPath` setting, falling back to a jar bundled next
  to the extension.

Everything except `extension.ts` is free of any `vscode` import, so it can
be unit tested with the Node.js built-in test runner (`node --test`)
without spinning up a real Extension Host. This keeps the test suite fast
while still covering the logic that is easy to get wrong (position
conversion, JSON validation, argument building, error messages).

## Data contract between the two halves

The analyzer's JSON output is the only contract between the CLI and the
extension (or any other consumer). It is documented in full in
[`diagnostics-schema.md`](./diagnostics-schema.md).
