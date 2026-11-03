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
      },
      "suggestion": "findAll"                     // optional: text that should replace `range`
    }
  ]
}
```

Notes:

- `line`/`character` are **1-based**, matching common editor conventions.
  Consumers that need 0-based positions (such as the VS Code API) must
  subtract 1 from each before constructing a `vscode.Position`.
- `range.end` is exclusive. Diagnostics about an attribute value (for example
  an unresolved `refid` or a mismatched `resultType`) cover the whole value,
  and `undefined-parameter` covers the undefined name itself. The structural
  checks (`malformed-xml`, `missing-namespace`, `duplicate-statement-id`,
  `duplicate-otherwise`, `when-outside-choose`, `otherwise-outside-choose`)
  still report a zero-width position (`start` equals `end`); consumers must
  accept both.
- The process exit code is non-zero whenever `summary.errorCount > 0`. The
  JSON is always emitted first, regardless of the exit code.
- `mapperFileCount` was added after the initial v1 schema. It distinguishes a
  workspace with no mapper XML from a clean one (both have `fileCount: 0`).
  Consumers should treat it as optional, since older analyzer jars omit it.
- `suggestion` is present only when the analyzer is confident about a fix
  for a misspelled name: `range` then covers exactly that name, and replacing
  it with `suggestion` fixes the problem. The VS Code extension offers it as a
  quick fix. Consumers should treat it as optional, since older analyzer jars
  omit it.
- `message` is in English unless `--lang ja` is given, which writes it in
  Japanese. Nothing else in the document changes with the language, so
  consumers should match on `ruleId`, never on `message`.
- Rule IDs are stable strings under the `mybatis-lint/` namespace; see
  `analyzer/src/main/java/com/mybatislint/analyzer/model/RuleIds.java` for the
  authoritative list.

## Server mode

`java -jar analyzer.jar --server` keeps running and analyzes once per request,
so callers that check repeatedly (the VS Code extension) skip JVM start-up and
reuse files parsed by earlier requests; a file is read again when its
modification time or size changes.

- A request is one line on stdin: the same arguments as the CLI
  (`--exclude`, `--disable`, `--sql-dialect`, `--lang`, the workspace path)
  joined with tab characters.
- The response on stdout is a `Content-Length: <bytes>` line followed by
  exactly that many bytes of UTF-8: the same JSON document the CLI prints.
- `Content-Length: 0` means the request was invalid or the analysis failed;
  the reason is written to stderr. The process keeps running.
- Notes such as skipped folders go to stderr, as in the CLI.
- An empty line or the end of stdin stops the server.
