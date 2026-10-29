package com.mybatislint.analyzer.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single analysis finding, tied to a file and a range within that file.
 *
 * <p>See {@code docs/diagnostics-schema.md} for the JSON schema this maps to.
 */
public final class Diagnostic {
    private final Path file;
    private final String uri;
    private final Range range;
    private final Severity severity;
    private final String ruleId;
    private final String message;
    // 範囲の文字列をこれに置き換えれば直る、という候補(打ち間違えた名前の正しい名前)。なければ null。
    private final String suggestion;

    public Diagnostic(Path file, String uri, Range range, Severity severity, String ruleId, String message) {
        this(file, uri, range, severity, ruleId, message, null);
    }

    public Diagnostic(Path file, String uri, Range range, Severity severity, String ruleId, String message,
                      String suggestion) {
        this.file = Objects.requireNonNull(file, "file");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.range = Objects.requireNonNull(range, "range");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.message = Objects.requireNonNull(message, "message");
        this.suggestion = suggestion;
    }

    public Path getFile() {
        return file;
    }

    public String getUri() {
        return uri;
    }

    public Range getRange() {
        return range;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getMessage() {
        return message;
    }

    /** 範囲の文字列を置き換える候補。なければ null。 */
    public String getSuggestion() {
        return suggestion;
    }
}
