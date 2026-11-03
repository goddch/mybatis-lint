# mybatis-lint

Static analysis engine and VS Code extension for MyBatis mapper XML and
dynamic SQL.

This is an **early-stage preview**. It deliberately does **not**
attempt full MyBatis/Oracle support yet, and false positives are possible.

The analyzer favors silence over false positives: whenever something cannot be
decided statically (`${...}` values, unresolved or ambiguous Java types,
references to mappers that are not in the opened folder, and so on) it reports
nothing. Only the XML and Java sources in the workspace are read; from MyBatis
configuration files only `<typeAlias alias>` names are used, settings and build
files are only used to tell which database the SQL is for, and compiled
classes and other modules are not loaded. The analyzer never connects to a
database.

## Installing

The VS Code extension ships with its own Java runtime, so Java does not need
to be installed. Platform-specific VSIX files are attached to each
[GitHub Release](https://github.com/goddch/mybatis-lint/releases) and are
also uploaded to the VS Code Marketplace (publisher `goddch`). After
installing, open a folder containing mapper XML and run
**MyBatis Lint: Check Workspace** from the Command Palette. Results appear in
the Problems panel, and the workspace is checked again whenever a mapper XML
or Java file is saved (`mybatisLint.runOnSave`). **MyBatis Lint: Show Log**
shows what each run did: the extension version, files analyzed, time taken, and skipped folders.

When a misspelled name has exactly one close match, the message ends with
"Did you mean 'userName'?" and the lightbulb offers a quick fix that replaces
it. This covers `unknown-select-alias`, `undefined-parameter`,
`unknown-parameter-property` and `unknown-result-property`. For
`unsafe-dollar-substitution` after an operator, the quick fix replaces
`${name}` with `#{name}`.

**Go to Definition** (F12 or Ctrl+click) jumps between mapper XML and Java:
from a statement's `id` to the Java mapper method and back, from
`<include refid>` to the `<sql>` fragment, and from `resultMap="..."` or
`<resultMap extends="...">` to the `<resultMap>`, including namespace-qualified
references to other files. It reads the files directly, so it works without a
check or a save.

**Find All References** (Shift+F12) on a `<sql>` / `<resultMap>` id, a
statement id or a reference to one lists every place it is used, across files
and in Java `@ResultMap`. **Rename** (F2) changes the id together with every
reference (`refid`, `resultMap`, `extends`, `<association>`/`<collection>`
`select`, `@ResultMap`), keeping namespace prefixes. Renaming a statement id
also renames the Java mapper method; with a Java extension installed its
callers are renamed too.

**SQL Preview** (Ctrl+Shift+V / Cmd+Shift+V in a mapper XML file, or the
preview button in the editor title) opens a panel beside the editor with the
plain SQL of the statement under the cursor, the way MyBatis assembles it:
`<include>` expanded (across files), `<where>` / `<set>` / `<trim>` applied and
`<foreach>` repeated. Switch each `<if>`, pick the `<choose>` branch and set how
many times `<foreach>` repeats in the panel. Parameters can be written as
`:name` (DBeaver and similar clients ask for the values when you run it), as
`?`, or with values typed into the panel; **Copy SQL** copies the result.
With **Pick branches from values** on, the `test` conditions are evaluated
with the typed values (following OGNL, so `type == 'A'` never matches the
String "A", as in MyBatis); branches that cannot be decided keep your choice.
The panel follows the VS Code display language (English or Japanese;
`mybatisLint.language` fixes one). See
[`docs/sql-preview.md`](docs/sql-preview.md).

**CodeLens** shows small links above definitions: how many places use each
`<sql>` and `<resultMap>` ("3 references"), the Java method of each statement
("Java: UserMapper.findById"), and above each Java mapper method the statement
it runs ("XML: UserMapper.xml"). Clicking one opens the places in a peek view
(`mybatisLint.codeLens` turns them off).

**Hover** over `#{name}`, `#{user.name}` or an `AS` alias to see its Java type
and where it comes from (a mapper method parameter or a property of a class),
over `resultType` / `parameterType` / `type` to see the class's properties, and
over a statement `id` to see the Java method's signature. **Completion** offers
the names `#{` can use (parameters, or the properties of a single bean
parameter, and the properties after `name.`), resultType properties after
`AS`, and the file's `<sql>` / `<resultMap>` ids in `refid`, `resultMap` and
`extends`. Both read the Java sources directly; when a type or method cannot
be found, nothing is shown.

In `<insert>` statements, the editor also shows which `INSERT INTO t (...)`
column each `VALUES (...)` value or `SELECT` item goes to, as faint text at the
end of the line, lined up within each statement (`mybatisLint.insertColumnHints`:
`all`, `differentNames` or `off`). This updates as you type and does not need a
save.

## Rules

`error` means the mapper is definitely broken; `error` diagnostics make the
CLI exit non-zero. Everything else is a `warning`.

| Rule ID (`mybatis-lint/...`) | Severity | What it reports |
|---|---|---|
| `malformed-xml` | error | XML that cannot be parsed |
| `missing-namespace` | error | `<mapper>` without a non-blank `namespace` |
| `duplicate-statement-id` | error | the same `select`/`insert`/`update`/`delete` id twice in one mapper file, or in two files of the same namespace (statements with `databaseId` are skipped); MyBatis fails at startup |
| `duplicate-otherwise` | error | more than one `<otherwise>` in a `<choose>` |
| `when-outside-choose` | error | `<when>` that is not a direct child of `<choose>` |
| `otherwise-outside-choose` | error | `<otherwise>` that is not a direct child of `<choose>` |
| `undefined-parameter` | error | `#{...}`, `${...}`, `<if test>`, `<when test>`, or `<foreach collection>` referring to a name that is not a parameter of the resolved Java mapper method ([details](docs/foreach-collection-validation.md)) |
| `unknown-parameter-property` | error | a property that the parameter object cannot provide: `#{userNmae}` when the only parameter is a bean without `@Param`, `#{user.userNmae}` for `@Param("user")`, or `#{u.userNmae}` for a `<foreach item="u">` over `List<UserDomain>`; fails at run time with "There is no getter for property" ([details](docs/mapper-binding-validation.md)) |
| `insert-column-count` | error | `INSERT INTO t (...)` whose column count differs from its `VALUES (...)` or `SELECT` list (ORA-00947 / ORA-00913); statements with `<if>` or `<include>` in those lists are skipped ([details](docs/insert-column-validation.md)) |
| `parameter-in-string-literal` | error | `#{...}` inside a SQL string literal such as `LIKE '%#{name}%'`; it is not bound and fails at run time ([details](docs/sql-pitfalls.md)) |
| `sql-syntax-error` | warning | SQL that the project's database cannot parse, such as `FORM` for `FROM` or a missing comma. Checked only when the database is known: the `mybatisLint.sqlDialect` setting, a statement's `databaseId`, or a single database found in the project's settings and build files (Oracle, PostgreSQL, MySQL). Dynamic SQL is built like the SQL preview; errors that appear only for some combinations of branches are not reported ([details](docs/sql-syntax-validation.md)) |
| `unsafe-dollar-substitution` | warning | `${...}` where a value is written (after `=`, `<>`, `LIKE` and the like, inside a string literal, or right inside `IN (...)`), which inserts the value into the SQL as is and allows SQL injection; `ORDER BY ${column}`, table names and values known to be numeric or boolean are not reported ([details](docs/sql-pitfalls.md)) |
| `invalid-jdbc-type` | error | a `jdbcType` that `org.apache.ibatis.type.JdbcType` does not have, in `#{name,jdbcType=...}`, `#{name:TYPE}` or a `jdbcType` attribute, including wrong case such as `varchar`, and a `mode` other than IN, OUT or INOUT; MyBatis fails when it loads the mapper ([details](docs/sql-pitfalls.md)) |
| `unknown-parameter-option` | error | an option `#{...}` does not accept, such as `jdbctype=` ([details](docs/sql-pitfalls.md)) |
| `single-char-literal-comparison` | warning | `<if test="type == 'A'">`: OGNL reads `'A'` as a char, so it never equals a String ([details](docs/sql-pitfalls.md)) |
| `numeric-empty-string-comparison` | warning | `<if test="status != ''">` on a numeric value: OGNL treats `''` as 0, so the condition is also false for 0 ([details](docs/sql-pitfalls.md)) |
| `dangling-sql-joiner` | error / warning | `WHERE` or `SET` written as text and followed by `<if>` (without `<where>`/`<set>`), where some combination of conditions leaves `AND`/`OR` or a comma at the start or end, as in `WHERE AND ...` or `name = ?, WHERE`. Error when it breaks every time that branch is included, warning when it depends on the other conditions ([details](docs/dynamic-clause-validation.md)) |
| `empty-dynamic-clause` | warning | `WHERE` or `SET` followed only by `<if>`, so nothing follows it when every condition is false ([details](docs/dynamic-clause-validation.md)) |
| `missing-mapper-statement` | warning | a Java mapper method with no statement of the same id in its mapper XML (reported in the Java file); fails at run time with `BindingException` ([details](docs/mapper-binding-validation.md)) |
| `unresolved-include` | error | `<include refid>` with no matching `<sql id>`, including `namespace.sqlId` references across files; namespaces with no mapper in the workspace are skipped ([details](docs/include-reference-validation.md)) |
| `duplicate-result-map-id` | error | the same top-level resultMap id twice in one namespace, across files |
| `result-map-and-type` | warning | a statement with both `resultMap` and `resultType` |
| `unresolved-type-alias` | warning | a type that looks like a custom alias but matches no workspace class (heuristic; [details](docs/type-alias-validation.md)) |
| `parameter-type-mismatch` | warning | `parameterType` that disagrees with the single argument of the Java mapper method ([details](docs/parameter-type-validation.md)) |
| `result-type-mismatch` | warning | `resultType` on a `<select>` that cannot be assigned to the Java mapper method's return type (simple cases only; subclasses and implementations are accepted, and types whose hierarchy cannot be followed in the workspace are skipped) |
| `unknown-result-property` | warning | a resultMap `property` with no matching field or setter on the mapped workspace class (Lombok and superclasses in the workspace are supported) |
| `unknown-select-alias` | warning | a column alias written with `AS` in a `<select resultType>` that matches no property of the result class, so MyBatis silently drops the column ([details](docs/select-alias-validation.md)) |
| `constructor-column-count` | error | a `<select resultType>` whose class has no no-arg constructor (for example Lombok `@Data` with only `final` fields) and fewer columns than its only constructor takes; MyBatis passes the columns to the constructor by position, so this fails at run time ([details](docs/constructor-mapping-validation.md)) |
| `constructor-positional-binding` | warning | for such a class with a Lombok-generated constructor, a field with no column of its name: it silently keeps the value of whichever column is at its position, so a misspelled alias can pass tests while the column order happens to match ([details](docs/constructor-mapping-validation.md)) |
| `result-map-column-not-selected` | warning | a resultMap `column` missing from the SELECT list of the only `<select>` that uses the resultMap, so the property stays null ([details](docs/result-map-validation.md#columns-missing-from-the-select-list)) |
| `unused-sql-fragment` | warning | a `<sql>` that no `<include refid>` in the workspace uses ([details](docs/unused-definition-validation.md)) |
| `unused-result-map` | warning | a `<resultMap>` that no statement, nested mapping, `extends` or Java `@ResultMap` uses ([details](docs/unused-definition-validation.md)) |
| `statement-without-method` | warning | a statement whose mapper interface has no method of that name, as after renaming a method; skipped when the interface is not in the workspace, extends another interface, or the id appears as a string in Java ([details](docs/unused-definition-validation.md)) |
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

### Silencing a rule

If a rule reports something that is not a problem in your project:

- Turn the rule off everywhere with the `mybatisLint.disabledRules` setting
  (CLI: `--disable <rule>`, repeatable). The `mybatis-lint/` prefix is optional.
  An unknown rule ID is reported on stderr and in the extension's log.
- Silence one place with a comment on the line above it. Write the directive
  and its rules on one line; with no rules, every rule is silenced.

  ```xml
  <!-- mybatis-lint-disable-next-line unknown-select-alias -->
  SELECT name AS displayName FROM users
  ```

  ```java
  // mybatis-lint-disable-next-line missing-mapper-statement
  List<User> findByCustomProvider(String key);
  ```

- Silence a whole file with `mybatis-lint-disable-file [rule ...]` anywhere in
  it.

## What is **not** implemented yet

Syntax checks for databases other than Oracle, PostgreSQL and MySQL, column-to-property validation beyond explicit
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

`--lang ja` writes the messages in Japanese (the default is `--lang en`). The
VS Code extension follows the display language, and the `mybatisLint.language`
setting (`auto` / `en` / `ja`) fixes one; it also applies to the SQL preview,
quick fixes, CodeLens and notifications. Rule IDs stay in English.

`java -jar build/libs/analyzer.jar --server` keeps the analyzer running and
reads one request per line (see [`docs/diagnostics-schema.md`](docs/diagnostics-schema.md#server-mode)).
The VS Code extension uses it so that checks after the first one take a
fraction of the time (`mybatisLint.keepAnalyzerRunning`, on by default).

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
`vsce publish --oidc` (OIDC trusted publishing, no stored token; the
publisher has a Trusted Publishing policy for this repository's
`package.yml`). If that job fails, the VSIX files on the Release can be
uploaded manually on the Marketplace management page.

## License

[Apache License 2.0](LICENSE). The analyzer jar includes JavaParser and
JSqlParser, both used under the Apache License 2.0 (each is dual-licensed).
The packaged VS Code extension also bundles an
OpenJDK runtime, which is distributed under its own license (GPLv2 with the
Classpath Exception); its license files are included under `jre/legal/`.
