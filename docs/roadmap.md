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
- **Java Mapper interface parameter resolution.** Cross-checking `#{...}`,
  `${...}`, `<if test="...">`, `<foreach>`, etc. against the parameters
  actually declared on the corresponding Java Mapper interface method
  (including `@Param`, single-parameter implicit binding, nested DTO
  properties, and OGNL expressions).
- **`resultType` / `resultMap` property validation.** Checking that SQL
  column aliases in a `<select>` correspond to real properties on the
  configured `resultType`, accounting for `mapUnderscoreToCamelCase`,
  Lombok-generated accessors, records, and `resultMap`-based mappings.
- **Dynamic SQL branch expansion.** Generating representative combinations
  of `<if>`/`<choose>`/`<foreach>` branches and validating the resulting SQL,
  with an explicit cap on how many combinations are generated and clear
  reporting of how many branches were (and were not) checked.
- **Additional mapper-level rules**, e.g. `resultMap`/`resultType` specified
  together, dangling `<include refid="...">` references, unresolved
  `typeAlias`es.
- **Incremental / as-you-type diagnostics** via a Language Server, once the
  analyzer core has stabilized enough to justify the added complexity.
- **Packaging/publishing** (VS Code Marketplace, Maven Central, etc.) is out
  of scope while this repository is private.

## Design decisions carried forward

- The analyzer stays a single Gradle module until there's a concrete reason
  to split it (e.g. a genuinely independent, reusable SQL-parsing module).
- The CLI-first approach (JSON over stdout) is kept even after an LSP is
  introduced, since it's useful for CI and non-editor tooling regardless.
