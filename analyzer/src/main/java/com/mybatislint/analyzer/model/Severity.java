package com.mybatislint.analyzer.model;

/** Severity level for a {@link Diagnostic}. */
public enum Severity {
    ERROR,
    WARNING;

    /** Lower-case JSON representation, e.g. {@code "error"}. */
    public String toJson() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
