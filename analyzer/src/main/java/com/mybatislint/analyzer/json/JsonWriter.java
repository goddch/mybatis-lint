package com.mybatislint.analyzer.json;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Severity;

import java.util.List;

/**
 * Serializes analysis results to a small, deterministic JSON document.
 *
 * <p>No third-party JSON library is used: the output shape is simple and
 * fixed, so a tiny hand-written writer keeps the analyzer's dependency
 * footprint minimal. See {@code docs/diagnostics-schema.md} for the schema.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(List<Diagnostic> diagnostics) {
        long errorCount = diagnostics.stream().filter(d -> d.getSeverity() == Severity.ERROR).count();
        long warningCount = diagnostics.stream().filter(d -> d.getSeverity() == Severity.WARNING).count();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"fileCount\": ").append(countDistinctFiles(diagnostics)).append(",\n");
        sb.append("    \"errorCount\": ").append(errorCount).append(",\n");
        sb.append("    \"warningCount\": ").append(warningCount).append("\n");
        sb.append("  },\n");
        sb.append("  \"diagnostics\": [\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic d = diagnostics.get(i);
            sb.append("    {\n");
            sb.append("      \"file\": \"").append(escape(d.getFile().toString().replace('\\', '/'))).append("\",\n");
            sb.append("      \"uri\": \"").append(escape(d.getUri())).append("\",\n");
            sb.append("      \"ruleId\": \"").append(escape(d.getRuleId())).append("\",\n");
            sb.append("      \"severity\": \"").append(d.getSeverity().toJson()).append("\",\n");
            sb.append("      \"message\": \"").append(escape(d.getMessage())).append("\",\n");
            sb.append("      \"range\": {\n");
            sb.append("        \"start\": { \"line\": ").append(d.getRange().getStart().getLine())
                    .append(", \"character\": ").append(d.getRange().getStart().getCharacter()).append(" },\n");
            sb.append("        \"end\": { \"line\": ").append(d.getRange().getEnd().getLine())
                    .append(", \"character\": ").append(d.getRange().getEnd().getCharacter()).append(" }\n");
            sb.append("      }\n");
            sb.append("    }").append(i < diagnostics.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static long countDistinctFiles(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::getFile).distinct().count();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
