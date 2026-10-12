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
    `parameterType="..."`, `resultType="..."`, nested association/collection
    `javaType="..."`, collection `ofType="..."`, discriminator `javaType="..."`,
    and inline discriminator case `resultType="..."` — a workspace-wide,
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
  - unknown direct `property="..."` on `<id>` / `<result>` /
    `<association>` / `<collection>` in a
    top-level `<resultMap type="fully.qualified.Class">` or inline nested
    `<association javaType="fully.qualified.Class">` /
    `<collection ofType="fully.qualified.Class">`, when no field or
    setter exists on the uniquely resolved workspace Java class
    (`mybatis-lint/unknown-result-property`, warning)
  - unresolved `extends="..."` parents on top-level `<resultMap>` declarations
    (`mybatis-lint/unresolved-result-map-extends`, warning), resolving local IDs
    and fully-qualified `namespace.resultMapId` references across mapper files
  - inheritance cycles among top-level resultMaps
    (`mybatis-lint/result-map-inheritance-cycle`, warning), including self-references
    and cycles across mapper files and namespaces
  - unresolved `resultMap="..."` references on top-level statements
    (`mybatis-lint/unresolved-statement-result-map`, warning), including
    cross-file resolution (comma-separated references are skipped)
  - unresolved `resultMap="..."` references on `<association>` /
    `<collection>` elements inside top-level resultMaps
    (`mybatis-lint/unresolved-nested-result-map`, warning), including
    cross-file resolution
  - unresolved `resultMap="..."` references on discriminator `<case>` elements
    inside top-level resultMaps
    (`mybatis-lint/unresolved-discriminator-result-map`, warning), including
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

The same check applies to direct `<id>` / `<result>` children of inline
`<association javaType="com.example.Address">` and
`<collection ofType="com.example.Item">`, including deeper nesting inside
top-level resultMaps. Each mapping uses only its immediate parent's explicit
type: collection `javaType` describes the container and is not used as the
element type. An explicit nested type can be checked even when its enclosing
mapping's type cannot be resolved. The association/collection's own `property`
is also checked against its immediate parent's explicit type, not against its
own `javaType`/`ofType`. For example, an association with `property="addres"`
warns if the enclosing Java class has neither that field nor a matching setter.
This parent-property check does not require the association/collection to declare
its own type. Omitted types are not inferred, and type compatibility is not checked.
Association/collection properties are also checked when the element specifies
`resultMap` or `select`: these attributes do not change the property's owner.
For example, `<association property="addres" resultMap="AddressMap"/>` warns
against the enclosing explicit Java type even if `AddressMap` resolves correctly.
This check is independent of reference resolution; a missing resultMap can also
produce the existing unresolved-reference warning.
Descendants of mappings with `resultMap` or `select` are still skipped; referenced
maps and nested select return types are not expanded or inferred. Mappings with
`extends` and their descendants are skipped. ResultMaps containing discriminators
remain entirely fail-open.

This is a conservative first step toward result mapping validation, not SQL
column validation. It skips aliases (including simple class names), unresolved
or duplicate Java types, interfaces, abstract/generic/nested classes, records,
classes with inheritance or annotations (including annotated members and
Lombok), `resultMap extends`, resultMaps containing discriminators,
blank/dynamic/nested/indexed properties,
constructor mappings, and properties whose immediate parent has no explicit
supported FQCN type. No MyBatis
configuration or compiled classpath is loaded.

### resultMap inheritance references

`<resultMap id="UserDetails" type="map" extends="BaseUser"/>` produces
`mybatis-lint/unresolved-result-map-extends` if `BaseUser` is not declared as a
top-level resultMap in the same namespace anywhere in the scanned workspace.
Fully-qualified parents such as `com.example.OtherMapper.BaseUser` are also
resolved across files, including forward references.

This rule checks parent existence at each link, not inheritance cycles or inherited
properties. Blank/dynamic (`${...}`) parents and missing/dynamic namespaces are
skipped. Nested mappings and duplicate
resultMap IDs are not checked by this rule. MyBatis configuration, annotations,
and external dependencies are not loaded, so an externally registered parent
may produce a warning; warnings do not change the CLI exit code.

### resultMap inheritance cycles

`<resultMap id="A" type="map" extends="B"/>` and
`<resultMap id="B" type="map" extends="A"/>` produce
`mybatis-lint/result-map-inheritance-cycle` warnings at each participating
`extends` attribute value. Self-references are also detected. Short parents
resolve within the current namespace; fully-qualified parents resolve across
workspace files. Current-namespace-qualified declaration IDs are normalized.
Single-file analysis detects local cycles too.

Only cycle members are reported, not resultMaps that merely inherit from a cycle.
Blank/dynamic IDs, parents, or namespaces, nested declarations, malformed/unreadable
XML, and paths through duplicate resultMap IDs are skipped. Missing parents remain
the responsibility of `mybatis-lint/unresolved-result-map-extends`. Configuration,
annotations, and external dependencies are not loaded; inherited properties are
not checked. These warnings do not change the CLI exit code.

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

### Nested mapping type aliases

Inside top-level resultMaps, explicit `javaType` values on `<association>` /
`<collection>` and `ofType` values on `<collection>` use the existing
`mybatis-lint/unresolved-type-alias` heuristic (warning). Deeper nested mappings
are included. For example, `<collection property="items" ofType="MissingItem"/>`
warns when no workspace class/interface simple name matches `MissingItem`
case-insensitively.

Blank/dynamic values, FQCNs, built-in aliases, and lowercase custom aliases are
skipped. Explicit types are checked even when a nested mapping also references
`resultMap`. Elements outside top-level resultMaps and `association ofType` are
not checked. This does not infer omitted types, validate collection/element
compatibility, or validate properties through aliases. Explicit FQCN nested
properties are checked separately as described above. External alias registrations can
produce false positives; warnings do not change the CLI exit code. See
[`docs/type-alias-validation.md`](docs/type-alias-validation.md).

### Discriminator type aliases

Inside top-level resultMaps (including deeper inline nesting), explicit
`<discriminator javaType="...">` and direct `<case resultType="...">` children
use the same `mybatis-lint/unresolved-type-alias` heuristic (warning).
For example, `<case value="admin" resultType="MissingAdmin"/>` warns if no
workspace class/interface/record simple name matches `MissingAdmin`,
case-insensitively.

Cases with a `resultMap` attribute are skipped for `resultType` validation:
MyBatis uses the referenced map instead of constructing an inline case map.
Their reference validation remains independent, and the discriminator's own
`javaType` is still checked. Blank/dynamic values, FQCNs, built-in aliases,
lowercase custom aliases, and elements outside top-level resultMaps are skipped.
Discriminator/case types within descendants of association/collection mappings
with `resultMap` or `select`, or cases with `resultMap`, are also skipped:
MyBatis does not construct those inline maps.
Omitted types are not inferred; discriminator values, type compatibility, and
mapping properties are not checked. External alias registrations can produce
false positives; warnings do not change the CLI exit code.

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

### Discriminator resultMap references

`<discriminator><case value="admin" resultMap="AdminMap"/></discriminator>`
inside a top-level `<resultMap>` produces
`mybatis-lint/unresolved-discriminator-result-map` (warning) if `AdminMap`
is not declared as a top-level resultMap in the current namespace anywhere
in the scanned workspace. Discriminators within inline nested mappings are
also checked. Local, fully-qualified `namespace.resultMapId`, forward, and
same-namespace cross-file references use the existing resultMap resolution.
Duplicate declarations still satisfy this existence check and are reported
separately by the duplicate-ID rule.

Only direct `<case>` children of `<discriminator>` inside top-level resultMaps
are checked. Blank/dynamic (`${...}`) and comma-separated values, missing/dynamic
namespaces, and inline cases without a `resultMap` reference are skipped.
This checks reference existence only, not discriminator values, mapping
compatibility, cycles through cases, or properties within discriminator mappings.
Malformed/unreadable XML is handled by normal XML analysis. Configuration,
annotations, and external dependencies are not loaded, so externally registered
resultMaps may produce warnings. Warnings do not change the CLI exit code.

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
