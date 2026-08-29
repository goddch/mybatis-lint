# mybatis-lint

Static analysis engine and VS Code extension for MyBatis mapper XML and
dynamic SQL.

This is a **private, early-stage** project. This first pull request
establishes a small, testable vertical slice — it deliberately does **not**
attempt full MyBatis/Oracle support yet. See [`docs/roadmap.md`](docs/roadmap.md)
for exactly what is and isn't implemented.

## What works today (MVP)

- A Java CLI (`analyzer/`) that recursively scans a workspace for MyBatis
  mapper XML files and reports:
  - malformed XML
  - missing/blank `<mapper namespace="...">`
  - duplicate statement IDs (`select`/`insert`/`update`/`delete`) within a
    mapper file
  - duplicate `<otherwise>` inside a `<choose>`
  - `<when>` / `<otherwise>` used outside `<choose>`
- Diagnostics are emitted as deterministic JSON on stdout (schema documented
  in [`docs/diagnostics-schema.md`](docs/diagnostics-schema.md)); the process
  exits non-zero when error-severity diagnostics are found.
- A VS Code extension (`vscode-extension/`) with a
  **`MyBatis Lint: Check Workspace`** command (`mybatisLint.checkWorkspace`)
  that runs the analyzer and publishes results to the Problems panel.

## What is **not** implemented yet

Oracle SQL syntax validation, Java Mapper parameter resolution, `resultType`
property validation, and dynamic SQL branch expansion are roadmap items —
see [`docs/roadmap.md`](docs/roadmap.md). Please don't rely on this tool for
those checks yet.

## Repository layout

```
analyzer/            Java 17 Gradle project — the analysis engine + CLI
vscode-extension/     TypeScript VS Code extension — a thin client
fixtures/             Sample valid/invalid mapper XML, used by tests and manual runs
docs/                 Architecture, roadmap, and diagnostics JSON schema
.github/workflows/    CI (Java + TypeScript)
```

See [`docs/architecture.md`](docs/architecture.md) for how the pieces fit
together and what extension points exist for future work.

## Prerequisites

- Java 17 (the Gradle wrapper is committed, so a separate Gradle install is
  not required)
- Node.js 20+ and npm (for the VS Code extension)
- VS Code, to run/debug the extension

## Building and testing

### Analyzer

```sh
cd analyzer
./gradlew test   # run the JUnit 5 test suite
./gradlew jar    # build analyzer/build/libs/analyzer.jar
```

### Running the analyzer CLI directly

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures
```

This prints a JSON diagnostics report to stdout and exits non-zero if any
error-severity diagnostics were found (the `fixtures/invalid` files are
intentionally invalid, so this is expected when scanning `fixtures/`).

### VS Code extension

```sh
cd vscode-extension
npm install
npm run compile   # type-check + emit JS to out/
npm run lint      # ESLint
npm test          # unit tests (node --test), no Extension Host required
```

### Launching/debugging the extension

1. Build the analyzer jar once: `cd analyzer && ./gradlew jar`.
2. Open the `vscode-extension/` folder in VS Code.
3. Set `mybatisLint.analyzerJarPath` in your VS Code settings to the
   absolute path of `analyzer/build/libs/analyzer.jar` (the jar isn't
   bundled with the extension yet — see `docs/roadmap.md`).
4. Press F5 (uses `.vscode/launch.json`) to launch an Extension Development
   Host window, open a folder containing mapper XML (e.g. this repo's
   `fixtures/` folder), and run **MyBatis Lint: Check Workspace** from the
   Command Palette.

## Roadmap

See [`docs/roadmap.md`](docs/roadmap.md) for the full list of planned work,
including Oracle SQL parsing, Java Mapper parameter/resultType validation,
and dynamic SQL branch expansion.

