package com.mybatislint.analyzer.json;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonWriterTest {

    @Test
    void writesExpectedFields() {
        Diagnostic diagnostic = new Diagnostic(
                Path.of("mappers/UserMapper.xml"),
                "file:///workspace/mappers/UserMapper.xml",
                Range.at(3, 5),
                Severity.ERROR,
                RuleIds.MISSING_NAMESPACE,
                "namespace is missing \"quoted\"");

        String json = JsonWriter.write(List.of(diagnostic));

        assertTrue(json.contains("\"file\": \"mappers/UserMapper.xml\""));
        assertTrue(json.contains("\"uri\": \"file:///workspace/mappers/UserMapper.xml\""));
        assertTrue(json.contains("\"ruleId\": \"" + RuleIds.MISSING_NAMESPACE + "\""));
        assertTrue(json.contains("\"severity\": \"error\""));
        assertTrue(json.contains("\"line\": 3"));
        assertTrue(json.contains("\"character\": 5"));
        assertTrue(json.contains("\\\"quoted\\\""));
        assertTrue(json.contains("\"errorCount\": 1"));
        assertTrue(json.contains("\"warningCount\": 0"));
    }

    @Test
    void writesEmptyDiagnosticsArrayWithoutError() {
        String json = JsonWriter.write(List.of());
        assertTrue(json.contains("\"diagnostics\": ["));
        assertTrue(json.contains("\"errorCount\": 0"));
        assertTrue(json.contains("\"fileCount\": 0"));
    }
}
