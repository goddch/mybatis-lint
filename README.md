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
  - duplicate top-level resultMap IDs within the same namespace, including
    declarations across mapper files (`mybatis-lint/duplicate-result-map-id`, error)
  - duplicate `<otherwise>` inside a `<choose>`
  - `<when>` / `<otherwise>` used outside `<choose>`
  - definite undefined Java mapper parameter references in resolved mapper
    methods (for example `#{missing}` or `<if test="status != null">` when
    the root name is not part of the Java method signature, `@Param` values,
    generic aliases, `_parameter`, or local `<bind>` / `<foreach>` names)
  - unresolved `<include refid="...">` references, including
    workspace-wide resolution for fully-qualified `namespace.sqlId` refs
    (dynamic `${...}` refids are intentionally ignored to avoid false positives)
  - statements that specify both `resultMap` and `resultType` (warning)
  - unresolved `typeAlias` candidates for `<resultMap type="...">`,
    `parameterType="..."`, and `resultType="..."` — a workspace-wide,
    fail-open heuristic based on matching Java class/interface simple names
    (warning; see [`docs/type-alias-validation.md`](docs/type-alias-validation.md))
  - mismatched `parameterType="..."` on `<select>/<insert>/<update>/<delete>`
    against the single-argument type of a uniquely resolved Java mapper
    method — FQCN/simple-name matching plus a curated built-in alias ↔
    primitive/boxed/String/Map/List mapping (warning, single-argument
    methods only, fail-open otherwise; see
    [`docs/parameter-type-validation.md`](docs/parameter-type-validation.md))
  - mismatched `resultType="..."` on a `<select>` that has no `resultMap`,
    against a uniquely resolved Java mapper method's return type — only
    when the return type is a single object type, a primitive/boxed type with
    its corresponding built-in alias, a built-in `map` alias with `Map<K, V>`,
    or a simple `List<T>`
    (warning; deliberately narrow scope, fail-open on `Cursor`/`void`/
    nested generics/arrays/unknown aliases/`${...}`)
  - unknown direct `<id property="...">` / `<result property="...">` in a
    top-level `<resultMap type="fully.qualified.Class">`, when no field or
    setter exists on the uniquely resolved workspace Java class
    (`mybatis-lint/unknown-result-property`, warning)
  - unresolved `extends="..."` parents on top-level `<resultMap>` declarations
    (`mybatis-lint/unresolved-result-map-extends`, warning), resolving local IDs
    and fully-qualified `namespace.resultMapId` references across mapper files
  - unresolved `resultMap="..."` references on top-level statements
    (`mybatis-lint/unresolved-statement-result-map`, warning), including
    cross-file resolution (comma-separated references are skipped)
  - unresolved `resultMap="..."` references on `<association>` /
    `<collection>` elements inside top-level resultMaps
    (`mybatis-lint/unresolved-nested-result-map`, warning), including
    cross-file resolution
- Diagnostics are emitted as deterministic JSON on stdout (schema documented
  in [`docs/diagnostics-schema.md`](docs/diagnostics-schema.md)); the process
  exits non-zero when error-severity diagnostics are found.
- A VS Code extension (`vscode-extension/`) with a
  **`MyBatis Lint: Check Workspace`** command (`mybatisLint.checkWorkspace`)
  that runs the analyzer and publishes results to the Problems panel.

### Explicit resultMap property validation

For example, `<resultMap type="com.example.User">` with
`<result column="name" property="naem"/>` produces a warning if the workspace
class `com.example.User` has neither a `naem` field nor a `setNaem(...)` method.
Private fields are supported, and property names are case-sensitive.

This is a conservative first step toward result mapping validation, not SQL
column validation. It skips aliases (including simple class names), unresolved
or duplicate Java types, interfaces, abstract/generic/nested classes, records,
classes with inheritance or annotations (including annotated members and
Lombok), `resultMap extends`, resultMaps containing discriminators,
blank/dynamic/nested/indexed properties,
constructors, and nested `<association>` / `<collection>` mappings. No MyBatis
configuration or compiled classpath is loaded.

### resultMap inheritance references

`<resultMap id="UserDetails" type="map" extends="BaseUser"/>` produces
`mybatis-lint/unresolved-result-map-extends` if `BaseUser` is not declared as a
top-level resultMap in the same namespace anywhere in the scanned workspace.
Fully-qualified parents such as `com.example.OtherMapper.BaseUser` are also
resolved across files, including forward references.

This checks parent existence at each link, not inheritance cycles or inherited
properties. Blank/dynamic (`${...}`) parents and missing/dynamic namespaces are
skipped. Nested mappings and duplicate
resultMap IDs are not checked by this rule. MyBatis configuration, annotations,
and external dependencies are not loaded, so an externally registered parent
may produce a warning; warnings do not change the CLI exit code.

### Statement resultMap references

`<select id="find" resultMap="UserMap"/>` produces
`UNRESOLVED_STATEMENT_RESULT_MAP` (`mybatis-lint/unresolved-statement-result-map`,
WARNING) if `UserMap` is not declared as a
top-level resultMap in the current namespace anywhere in the scanned workspace.
The same check applies to top-level `<insert>`, `<update>`, and `<delete>`.
Fully-qualified `namespace.resultMapId` references and forward declarations are
supported. A missing ID produces a warning at the `resultMap` attribute value.
Duplicate local IDs are collapsed to a single entry; later declarations do not
affect this existence check.

Fail-open: blank values, attributes containing `${...}` or a comma
(multiple resultMaps), and missing/dynamic namespaces are skipped.
A nonblank `resultType` also skips this check to avoid duplicating the existing
`RESULT_MAP_AND_TYPE` warning. Malformed/unreadable mapper XML is handled by the
normal XML analysis, not reported again by this rule.
This validates existence only, not mapping compatibility;
nested mappings and duplicate ID diagnostics remain out of scope.
A type alias such as
`map` is not a resultMap reference unless a resultMap with that ID is declared.
Externally registered resultMaps may produce warnings because configuration,
annotations, and dependencies are not loaded. Warnings do not change the CLI
exit code.

### Nested resultMap references

`<association>` and `<collection>` elements inside a top-level `<resultMap>`
are checked when they specify `resultMap="..."`. An unresolved reference produces
`UNRESOLVED_NESTED_RESULT_MAP`
(`mybatis-lint/unresolved-nested-result-map`, WARNING). Local, fully-qualified,
forward, and same-namespace cross-file references are supported.

Blank/dynamic (`${...}`) and comma-separated values, or missing/dynamic
namespaces, are skipped. Inline nested `<resultMap>` declarations are not
interpreted as references. Because configuration, annotations, and dependencies
are not loaded, externally registered resultMaps may produce warnings; warnings
do not change the CLI exit code.

### Duplicate resultMap IDs

Top-level `<resultMap id="Base">` declarations must be unique within their
namespace across the scanned workspace. `Base` and `example.Mapper.Base` are
treated as the same ID when the mapper namespace is `example.Mapper`.
`mybatis-lint/duplicate-result-map-id` reports an error at the `id` attribute
value of every conflicting declaration, including the first, so diagnostics
do not depend on which file is analyzed first. Errors cause a non-zero CLI
exit code. Single-file analysis also detects local duplicates.

IDs are case-sensitive, and equal IDs in different namespaces do not conflict.
Missing/blank/dynamic (`${...}`) IDs, missing/blank/dynamic namespaces, nested
declarations, and malformed/unreadable XML are not indexed for this check.
This rule does not change resultMap reference existence checks or inspect
externally registered resultMaps, inheritance cycles, or nested mapping types.

## What is **not** implemented yet

Oracle SQL syntax validation, SQL-column-to-DTO property validation, and
dynamic SQL branch expansion are roadmap items — see
[`docs/roadmap.md`](docs/roadmap.md).
Please don't rely on this tool for those checks yet.

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
3. If needed, set `mybatisLint.analyzerJarPath` in your VS Code settings to the
   absolute path of `analyzer/build/libs/analyzer.jar` (during development, the
   extension also tries to auto-detect this sibling jar when it is not bundled
   yet — see `docs/roadmap.md`).
4. If `java` is not on your VS Code PATH, the extension also tries `JAVA_HOME`
   and SDKMAN's current Java automatically; otherwise set
   `mybatisLint.javaPath` explicitly.
5. Press F5 (uses `.vscode/launch.json`) to launch an Extension Development
   Host window, open a folder containing mapper XML (e.g. this repo's
   `fixtures/` folder), and run **MyBatis Lint: Check Workspace** from the
   Command Palette.

For a repeatable end-to-end smoke test, see
[`docs/manual-testing.md`](docs/manual-testing.md).

## Roadmap

See [`docs/roadmap.md`](docs/roadmap.md) for the full list of planned work,
including Oracle SQL parsing, deeper resultType property validation, and
dynamic SQL branch expansion.
