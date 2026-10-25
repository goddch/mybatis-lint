# Analyzer diagnostics JSON schema (v1)

The analyzer CLI writes a single JSON document to stdout. Nothing else is
written to stdout, so the output is always safe to parse directly.

```jsonc
{
  "schemaVersion": 1,
  "summary": {
    "mapperFileCount": 3, // number of analyzed files whose root element is <mapper> (malformed XML is not counted)
    "fileCount": 4,      // number of distinct files that produced at least one diagnostic
    "errorCount": 6,      // number of diagnostics with severity "error"
    "warningCount": 0     // number of diagnostics with severity "warning"
  },
  "diagnostics": [
    {
      "file": "invalid/DuplicateIdMapper.xml",   // path, relative to the workspace root passed on the CLI, forward-slash separated
      "uri": "file:///abs/path/to/DuplicateIdMapper.xml", // absolute file:// URI, suitable for vscode.Uri.parse
      "ruleId": "mybatis-lint/duplicate-statement-id",
      "severity": "error",                        // "error" | "warning"
      "message": "Duplicate statement id 'findById' in this mapper file.",
      "range": {
        "start": { "line": 9, "character": 63 },   // 1-based line, 1-based character
        "end":   { "line": 9, "character": 63 }
      }
    }
  ]
}
```

Notes:

- `line`/`character` are **1-based**, matching common editor conventions.
  Consumers that need 0-based positions (such as the VS Code API) must
  subtract 1 from each before constructing a `vscode.Position`.
- `range.start` and `range.end` are currently always equal (a zero-width
  position at the offending element's opening tag). Future rules may report
  wider ranges.
- The process exit code is non-zero whenever `summary.errorCount > 0`. The
  JSON is always emitted first, regardless of the exit code.
- `mapperFileCount` was added after the initial v1 schema. It distinguishes a
  workspace with no mapper XML from a clean one (both have `fileCount: 0`).
  Consumers should treat it as optional, since older analyzer jars omit it.
- Rule IDs are stable strings under the `mybatis-lint/` namespace; see
  `analyzer/src/main/java/com/mybatislint/analyzer/model/RuleIds.java` for the
  authoritative list.
