# mybatis-lint

Static analysis engine and VS Code extension for MyBatis mapper XML and
dynamic SQL.

This is an **early-stage preview**. It deliberately does **not**
attempt full MyBatis/Oracle support yet, and false positives are possible.

The analyzer favors silence over false positives: whenever something cannot be
decided statically (`${...}` values, unresolved or ambiguous Java types,
references to mappers that are not in the opened folder, and so on) it reports
nothing. Only the XML and Java sources in the workspace are read; from MyBatis
configuration files only `<typeAlias alias>` names are used, and compiled
classes and other modules are not loaded.

## Installing

The VS Code extension ships with its own Java runtime, so Java does not need
to be installed. Platform-specific VSIX files are attached to each
[GitHub Release](https://github.com/goddch/mybatis-lint/releases) and are
also uploaded to the VS Code Marketplace (publisher `goddch`). After
installing, open a folder containing mapper XML and run
**MyBatis Lint: Check Workspace** from the Command Palette. Results appear in
the Problems panel, and the workspace is checked again whenever a mapper XML
or Java file is saved (`mybatisLint.runOnSave`). **MyBatis Lint: Show Log**
shows what each run did: files analyzed, time taken, and skipped folders.

## Rules

`error` means the mapper is definitely broken; `error` diagnostics make the
CLI exit non-zero. Everything else is a `warning`.

| Rule ID (`mybatis-lint/...`) | Severity | What it reports |
|---|---|---|
| `malformed-xml` | error | XML that cannot be parsed |
| `missing-namespace` | error | `<mapper>` without a non-blank `namespace` |
| `duplicate-statement-id` | error | the same `select`/`insert`/`update`/`delete` id twice in one mapper file |
| `duplicate-otherwise` | error | more than one `<otherwise>` in a `<choose>` |
| `when-outside-choose` | error | `<when>` that is not a direct child of `<choose>` |
| `otherwise-outside-choose` | error | `<otherwise>` that is not a direct child of `<choose>` |
| `undefined-parameter` | error | `#{...}`, `${...}`, `<if test>`, `<when test>`, or `<foreach collection>` referring to a name that is not a parameter of the resolved Java mapper method ([details](docs/foreach-collection-validation.md)) |
| `unresolved-include` | error | `<include refid>` with no matching `<sql id>`, including `namespace.sqlId` references across files; namespaces with no mapper in the workspace are skipped ([details](docs/include-reference-validation.md)) |
| `duplicate-result-map-id` | error | the same top-level resultMap id twice in one namespace, across files |
| `result-map-and-type` | warning | a statement with both `resultMap` and `resultType` |
| `unresolved-type-alias` | warning | a type that looks like a custom alias but matches no workspace class (heuristic; [details](docs/type-alias-validation.md)) |
| `parameter-type-mismatch` | warning | `parameterType` that disagrees with the single argument of the Java mapper method ([details](docs/parameter-type-validation.md)) |
| `result-type-mismatch` | warning | `resultType` on a `<select>` that cannot be assigned to the Java mapper method's return type (simple cases only; subclasses and implementations are accepted, and types whose hierarchy cannot be followed in the workspace are skipped) |
| `unknown-result-property` | warning | a resultMap `property` with no matching field or setter on the mapped workspace class (Lombok and superclasses in the workspace are supported) |
| `unknown-select-alias` | warning | a column alias written with `AS` in a `<select resultType>` that matches no property of the result class, so MyBatis silently drops the column ([details](docs/select-alias-validation.md)) |
| `unresolved-result-map-extends` | warning | `<resultMap extends>` pointing to a resultMap that does not exist |
| `result-map-inheritance-cycle` | warning | resultMaps whose `extends` chain loops |
| `unresolved-statement-result-map` | warning | a statement's `resultMap` pointing to a resultMap that does not exist |
| `unresolved-nested-result-map` | warning | an `<association>`/`<collection>` `resultMap` pointing to a resultMap that does not exist |
| `unresolved-discriminator-result-map` | warning | a discriminator `<case>` `resultMap` pointing to a resultMap that does not exist |
| `duplicate-discriminator-case-value` | warning | the same `<case value>` twice in one `<discriminator>` |

The resultMap rules have many fail-open conditions; see
[`docs/result-map-validation.md`](docs/result-map-validation.md).
The authoritative list of rule IDs is
[`RuleIds.java`](analyzer/src/main/java/com/mybatislint/analyzer/model/RuleIds.java).

The CLI writes the diagnostics as JSON to stdout
([`docs/diagnostics-schema.md`](docs/diagnostics-schema.md)).

## What is **not** implemented yet

Oracle SQL syntax validation, column-to-property validation beyond explicit
`AS` aliases (unaliased columns, `resultMap` statements, `*` and `<include>`
in the select list), and dynamic SQL branch expansion are roadmap items — see
[`docs/roadmap.md`](docs/roadmap.md).
Please don't rely on this tool for those checks yet.

## Repository layout

```
analyzer/            Java 17 Gradle project — the analysis engine + CLI
vscode-extension/     TypeScript VS Code extension — a thin client
fixtures/             Sample valid/invalid mapper XML, used by tests and manual runs
docs/                 Architecture, roadmap, diagnostics JSON schema, and rule details
.github/workflows/    CI (Java + TypeScript) and VSIX packaging
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

To skip part of the workspace, pass `--exclude <glob>` before the workspace
path, once per pattern (for example `--exclude docker --exclude 'modules/*/generated'`).
Globs are relative to the workspace and use `/`; a pattern without `/` matches
a folder or file name at any depth. Folders that cannot be read are skipped
automatically. In VS Code, use the `mybatisLint.exclude` setting.

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
3. The extension finds `analyzer/build/libs/analyzer.jar` automatically when
   no jar is bundled. To use another jar, set `mybatisLint.analyzerJarPath` to
   its absolute path.
4. The extension looks for Java in this order: the `mybatisLint.javaPath`
   setting, the bundled runtime in `vscode-extension/jre/` (created by
   `npm run bundle`; delete it to go back to auto-detection), `JAVA_HOME`,
   SDKMAN's current Java, and finally `java` on the PATH.
5. Press F5 (uses `.vscode/launch.json`) to launch an Extension Development
   Host window, open a folder containing mapper XML (e.g. this repo's
   `fixtures/` folder), and run **MyBatis Lint: Check Workspace** from the
   Command Palette.

For a repeatable end-to-end smoke test, see
[`docs/manual-testing.md`](docs/manual-testing.md).

### Packaging the extension (bundled Java runtime)

The packaged extension ships its own minimal Java 17 runtime (built with
`jlink`), so users do not need Java installed. Because `jlink` only produces a
runtime for the OS it runs on, a platform-specific VSIX is built per target.

```sh
cd analyzer && ./gradlew jar && cd ../vscode-extension
JAVA_HOME=/path/to/jdk-17 npm run bundle   # copies analyzer.jar, creates jre/, smoke-tests it
npx vsce package --target linux-x64   # Node 20+ required
```

`.github/workflows/package.yml` builds VSIX files for `linux-x64`, `win32-x64`,
`darwin-arm64`, and `darwin-x64` on manual dispatch or `v*` tags and uploads
them as workflow artifacts. Pushing a `v*` tag additionally attaches them to a
GitHub Release (the tag must match the `package.json` version), then tries to
publish them to the Marketplace (publisher `goddch`) with
`vsce publish --oidc` (OIDC trusted publishing, no stored token). This is still
experimental in vsce 4.0.0; if that job fails, the VSIX files on the Release are
uploaded manually on the Marketplace management page.

## License

[Apache License 2.0](LICENSE). The packaged VS Code extension also bundles an
OpenJDK runtime, which is distributed under its own license (GPLv2 with the
Classpath Exception); its license files are included under `jre/legal/`.
