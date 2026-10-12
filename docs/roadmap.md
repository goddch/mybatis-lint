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
  - duplicate top-level resultMap IDs within the same namespace, including
    declarations across workspace files (`mybatis-lint/duplicate-result-map-id`,
    error). Short and current-namespace-qualified declaration IDs are normalized
    consistently with reference resolution. Every conflicting declaration is
    reported at its `id` attribute value, including the first. IDs are
    case-sensitive; different namespaces do not conflict. Missing/blank/dynamic
    IDs or namespaces, nested declarations, and malformed/unreadable XML are
    skipped. Single-file analysis also detects local duplicates.
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
    `parameterType="..."`, `resultType="..."`, association/collection
    `javaType="..."`, collection `ofType="..."`, discriminator `javaType="..."`,
    and inline discriminator case `resultType="..."` (warning; a workspace-wide
    heuristic, not a guaranteed-correct resolution — see
    [`docs/type-alias-validation.md`](type-alias-validation.md))
    Nested mapping types are checked only inside top-level resultMaps, including
    deeper nesting. Blank/dynamic values, FQCNs, built-in aliases, and lowercase
    custom aliases are skipped. Omitted types are not inferred; collection/element
    compatibility and properties through aliases are not checked.
    Discriminator types and direct case types are also checked within top-level
    resultMaps, including deeper nesting. A case with a `resultMap` attribute
    uses a referenced map, so its `resultType` is skipped; discriminator
    `javaType` and case reference validation remain independent. Discriminator
    values, type compatibility, and mapping properties are not checked.
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
  - unknown direct `<id>` / `<result>` / `<association>` / `<collection>`
    properties on a top-level
    `<resultMap type="fully.qualified.Class">` or inline nested
    `<association javaType="fully.qualified.Class">` /
    `<collection ofType="fully.qualified.Class">` (warning;
    `mybatis-lint/unknown-result-property`). This first property-validation
    slice checks fields (including private fields) and one-argument setters
    on uniquely resolved, unannotated, non-generic concrete workspace classes
    with no superclass/interfaces. Aliases, duplicate/unresolved types,
    records, nested classes, constructor mappings, `resultMap extends`,
    resultMaps containing discriminators,
    and blank/dynamic/nested/indexed properties remain fail-open.
    Deeper inline association/collection nesting uses each immediate parent's
    explicit type, without inferring omitted types or treating collection
    `javaType` as its element type. Descendant properties within nested mappings
    with `resultMap`, `select`, or `extends` are skipped. Association/collection
    properties themselves are checked against the immediate parent's explicit
    type, even if their own type is omitted. Mappings with `resultMap` or `select`
    are now checked for their own association/collection `property`, independently
    of reference resolution; their descendants remain skipped. Mappings with
    `extends` remain skipped for this check too. Type compatibility is not checked.
  - unresolved `extends` parents on top-level resultMaps
    (`mybatis-lint/unresolved-result-map-extends`, warning). Local IDs resolve
    within the current namespace across workspace files; fully-qualified
    `namespace.resultMapId` parents and forward references are supported.
    Blank/dynamic parents and missing/dynamic namespaces are skipped.
    This checks existence only, not cycles or inherited properties; externally
    registered parents are not indexed and may produce warnings.
   - inheritance cycles among top-level resultMaps
    (`mybatis-lint/result-map-inheritance-cycle`, warning). Self-references and
    multi-map cycles, including cross-file/cross-namespace references, are
    reported at each cycle member's `extends` attribute value. Incoming
    descendants are not reported. Short parents resolve in the current namespace;
    current-namespace-qualified declaration IDs are normalized. Single-file
    analysis supports local cycles. Blank/dynamic IDs, parents, or namespaces,
    nested declarations, malformed/unreadable XML, and paths through duplicate
    IDs remain fail-open. Missing parents keep the existing unresolved-extends
    diagnostic; inherited properties and external registrations are not checked.
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
     registered resultMaps may produce warnings.
   - unresolved `resultMap` references on `<association>` and `<collection>`
     elements inside top-level resultMaps
     (`UNRESOLVED_NESTED_RESULT_MAP`,
     `mybatis-lint/unresolved-nested-result-map`, WARNING). Local,
     fully-qualified, forward, and same-namespace cross-file references resolve.
     Blank/dynamic values, comma-separated values, missing/dynamic namespaces,
     and elements outside top-level resultMaps are skipped. Inline nested
     declarations are not interpreted as references; externally registered
     resultMaps may produce warnings.
   - unresolved `resultMap` references on direct `<case>` children of
     `<discriminator>` inside top-level resultMaps, including inline nested
     mappings (`mybatis-lint/unresolved-discriminator-result-map`, warning).
     Local, fully-qualified, forward, and same-namespace cross-file references
     use the existing resultMap index. Duplicate declarations satisfy this
     existence check and retain separate duplicate-ID diagnostics.
     Blank/dynamic or comma-separated values, missing/dynamic namespaces,
     inline cases without references, and cases outside this context are skipped.
     Malformed/unreadable XML is handled by normal XML analysis. Externally
     registered resultMaps may produce warnings. Discriminator values, type
     compatibility, cycles through cases, and discriminator mapping properties
     remain out of scope.
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
  Explicitly FQCN-typed inline nested properties and association/collection
  properties on their immediate parent types are also checked, including the
  association/collection's own property when it uses `resultMap` or `select`.
  Remaining work includes properties within inferred/referenced nested mappings and collection/
  association type compatibility beyond alias candidates, as well as checking that SQL
  column aliases in a `<select>` correspond to real properties on the
  configured `resultType`, accounting for `mapUnderscoreToCamelCase`,
  Lombok-generated accessors, records, and `resultMap`-based mappings.
- **Dynamic SQL branch expansion.** Generating representative combinations
  of `<if>`/`<choose>`/`<foreach>` branches and validating the resulting SQL,
  with an explicit cap on how many combinations are generated and clear
  reporting of how many branches were (and were not) checked.
- **Additional mapper-level rules**, e.g. other cross-reference checks not
  covered by the current SQL fragment include rule or the typeAlias
  heuristic. ResultMap ID uniqueness, inheritance cycles,
  parent existence for `resultMap extends`,
  statement `resultMap` references, and nested association/collection
  and discriminator case `resultMap` references are now checked, but inherited
  and discriminator mapping property validation remains fail-open. Explicit
  discriminator/inline case type alias candidates are checked; discriminator
  values and type compatibility remain unvalidated.
- **Incremental / as-you-type diagnostics** via a Language Server, once the
  analyzer core has stabilized enough to justify the added complexity.
- **Packaging/publishing** (VS Code Marketplace, Maven Central, etc.) is out
  of scope while this repository is private.

## Design decisions carried forward

- The analyzer stays a single Gradle module until there's a concrete reason
  to split it (e.g. a genuinely independent, reusable SQL-parsing module).
- The CLI-first approach (JSON over stdout) is kept even after an LSP is
  introduced, since it's useful for CI and non-editor tooling regardless.
