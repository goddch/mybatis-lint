# Roadmap

This project intentionally started with a small, testable vertical slice
rather than attempting full MyBatis/Oracle support at once. This document
tracks what's implemented today versus what's planned, so the README and
extension descriptions never overstate current capabilities.

## Implemented

- Recursive discovery of mapper XML files (ignoring `.git`, `.gradle`,
  `build`, `target`, `node_modules`, `--exclude` globs, and unreadable
  folders) and safe XML parsing (XXE disabled, no external DTD download).
- The rules listed in the [README](../README.md#rules). Detailed behavior and
  fail-open conditions are in the per-rule documents under `docs/`, most
  notably [`result-map-validation.md`](result-map-validation.md).
- Deterministic JSON output on stdout, non-zero exit code on errors
  ([`diagnostics-schema.md`](diagnostics-schema.md)).
- A VS Code command (`MyBatis Lint: Check Workspace`) that runs the analyzer
  over the current workspace and publishes results as VS Code diagnostics.
- Packaging with a bundled Java 17 runtime. `v*` tags attach
  platform-specific VSIX files to a GitHub Release and then try to publish them
  to the Marketplace with OIDC trusted publishing (experimental in vsce; the
  fallback is a manual upload of the Release files). v0.1.0 is the first
  preview release.

## Not yet implemented (roadmap)

These are explicitly **not** implemented yet, and should not be described as
working in user-facing docs until they land:

- **Oracle SQL syntax validation.** No SQL parser is included yet. The plan
  is to add a `sqlparser` extension point in the analyzer and evaluate an
  existing Java SQL parser with Oracle dialect support (e.g. Druid's parser
  or JSqlParser) against real, anonymized mapper SQL before committing to
  one, rather than assuming either is a perfect match up front.
- **SQL column to property validation.** Checking that the column aliases in
  a `<select>` correspond to real properties of its `resultType` or
  `resultMap`, accounting for `mapUnderscoreToCamelCase`, Lombok-generated
  accessors, and records. Today only the `property` attributes written in a
  resultMap are checked.
- **Broader resultMap validation.** Inferring collection element types,
  following referenced nested mappings and inherited (`extends`) properties,
  and checking type compatibility and discriminator values. These are all
  fail-open today ([`result-map-validation.md`](result-map-validation.md)).
- **Dynamic SQL branch expansion.** Generating representative combinations
  of `<if>`/`<choose>`/`<foreach>` branches and validating the resulting SQL,
  with an explicit cap on how many combinations are generated and clear
  reporting of how many branches were (and were not) checked.
- **Additional mapper-level rules**, e.g. other cross-reference checks not
  covered by the existing include, resultMap, and typeAlias rules.
- **Incremental / as-you-type diagnostics** via a Language Server, once the
  analyzer core has stabilized enough to justify the added complexity.

## Design decisions carried forward

- The analyzer stays a single Gradle module until there's a concrete reason
  to split it (e.g. a genuinely independent, reusable SQL-parsing module).
- The CLI-first approach (JSON over stdout) is kept even after an LSP is
  introduced, since it's useful for CI and non-editor tooling regardless.
