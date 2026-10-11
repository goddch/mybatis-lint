# Roadmap

This project intentionally started with a small, testable vertical slice
rather than attempting full MyBatis/Oracle support in one PR. This document
tracks what's implemented today versus what's planned, so the README and
extension descriptions never overstate current capabilities.

## Implemented (this PR)

- Recursive discovery of mapper XML files (ignoring `.git`, `.gradle`,
  `build`, `target`, `node_modules`).
- Safe XML parsing: XXE disabled, no external DTD download required.
- Diagnostics for:
  - malformed XML
  - missing/blank `<mapper namespace="...">`
  - duplicate statement IDs (`select`/`insert`/`update`/`delete`) within one
    mapper file
  - duplicate `<otherwise>` inside a single `<choose>`
  - `<when>` used outside `<choose>`
  - `<otherwise>` used outside `<choose>`
  - definite undefined Java mapper parameter references in resolved mapper
    methods (`#{...}`, `${...}`, `<if test>`, `<when test>`, and
    `<foreach collection>`)
  - dangling `<include refid="...">` references, including fully-qualified
    `namespace.sqlId` refs to SQL fragments in other mapper files
  - `resultMap` and `resultType` specified together on a statement (warning)
  - unresolved `typeAlias` candidates in `<resultMap type="...">`,
    `parameterType="..."`, and `resultType="..."` (warning; a workspace-wide
    heuristic, not a guaranteed-correct resolution — see
    [`docs/type-alias-validation.md`](type-alias-validation.md))
  - `parameterType="..."` on a statement that doesn't match the single
    argument type of a uniquely resolved Java mapper method (warning;
    single-argument methods only, fail-open on ambiguous/unresolved methods,
    0 or 2+ arguments, `${...}` dynamic values, and low-confidence Java type
    shapes such as generics/type-variables — see
    [`docs/parameter-type-validation.md`](parameter-type-validation.md))
  - `resultType="..."` on a `<select>` without `resultMap` that doesn't match
    a uniquely resolved Java mapper method's return type (warning; only
    when the return type is a single object type, a primitive/boxed type with
    its corresponding built-in alias, built-in `map` alias with `Map<K, V>`,
    or a simple `List<T>`, fail-open on
    `resultMap`, `Cursor`/
    `void`, nested generics, arrays, unknown aliases, and `${...}` dynamic
    values — scope intentionally kept very small)
  - unknown direct `<id>` / `<result>` properties on a top-level
    `<resultMap type="fully.qualified.Class">` (warning;
    `mybatis-lint/unknown-result-property`). This first property-validation
    slice checks fields (including private fields) and one-argument setters
    on uniquely resolved, unannotated, non-generic concrete workspace classes
    with no superclass/interfaces. Aliases, duplicate/unresolved types,
    records, nested classes/mappings, constructor mappings, `resultMap extends`,
    resultMaps containing discriminators,
    and blank/dynamic/nested/indexed properties remain fail-open.
  - unresolved `extends` parents on top-level resultMaps
    (`mybatis-lint/unresolved-result-map-extends`, warning). Local IDs resolve
    within the current namespace across workspace files; fully-qualified
    `namespace.resultMapId` parents and forward references are supported.
    Blank/dynamic parents and missing/dynamic namespaces are skipped.
    This checks existence only, not cycles or inherited properties; externally
    registered parents are not indexed and may produce warnings.
   - unresolved `resultMap` references on top-level
     `<select>`/`<insert>`/`<update>`/`<delete>` statements
     (`UNRESOLVED_STATEMENT_RESULT_MAP`,
     `mybatis-lint/unresolved-statement-result-map`, WARNING). Local IDs resolve
     within the current namespace across workspace files; fully-qualified
     references and forward declarations are supported. Duplicate local IDs
     collapse to one entry. Fail-open on blank/dynamic attributes, comma-separated
     lists, missing/dynamic namespaces, and nonblank `resultType` (already reported
     by `RESULT_MAP_AND_TYPE`). Malformed/unreadable XML is handled by normal XML
     analysis without duplicate reporting. This checks existence only; externally
     registered resultMaps may produce warnings, and nested mapping references
     are not checked.
- Deterministic JSON output on stdout, non-zero exit code on errors.
- A VS Code command (`MyBatis Lint: Check Workspace`) that runs the analyzer
  over the current workspace and publishes results as VS Code diagnostics.

## Not yet implemented (roadmap)

These are explicitly **not** implemented yet, and should not be described as
working in user-facing docs until they land:

- **Oracle SQL syntax validation.** No SQL parser is included yet. The plan
  is to add a `sqlparser` extension point in the analyzer and evaluate an
  existing Java SQL parser with Oracle dialect support (e.g. Druid's parser
  or JSqlParser) against real, anonymized mapper SQL before committing to
  one, rather than assuming either is a perfect match up front.
- **Broader `resultType` / `resultMap` property validation.** Explicit, direct
  `resultMap` properties on plain FQCN-specified classes are now checked.
  Remaining work includes checking that SQL
  column aliases in a `<select>` correspond to real properties on the
  configured `resultType`, accounting for `mapUnderscoreToCamelCase`,
  Lombok-generated accessors, records, and `resultMap`-based mappings.
- **Dynamic SQL branch expansion.** Generating representative combinations
  of `<if>`/`<choose>`/`<foreach>` branches and validating the resulting SQL,
  with an explicit cap on how many combinations are generated and clear
  reporting of how many branches were (and were not) checked.
- **Additional mapper-level rules**, e.g. other cross-reference checks not
  covered by the current SQL fragment include rule or the typeAlias
  heuristic (for example validating `<association>`/`<collection>` nested
  result mappings, duplicate resultMap IDs,
  and resultMap inheritance cycles). Parent existence for `resultMap extends`
  and statement `resultMap` references are now checked, but inherited property
  validation remains fail-open.
- **Incremental / as-you-type diagnostics** via a Language Server, once the
  analyzer core has stabilized enough to justify the added complexity.
- **Packaging/publishing** (VS Code Marketplace, Maven Central, etc.) is out
  of scope while this repository is private.

## Design decisions carried forward

- The analyzer stays a single Gradle module until there's a concrete reason
  to split it (e.g. a genuinely independent, reusable SQL-parsing module).
- The CLI-first approach (JSON over stdout) is kept even after an LSP is
  introduced, since it's useful for CI and non-editor tooling regardless.
