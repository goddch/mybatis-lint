package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperXmlAnalyzerTest {

    private static final Path FIXTURES_ROOT = findFixturesRoot();

    private final MapperXmlAnalyzer analyzer = new MapperXmlAnalyzer();

    @Test
    void validMapperHasNoDiagnostics() {
        Path file = FIXTURES_ROOT.resolve("valid/UserMapper.xml");
        List<Diagnostic> diagnostics = analyzer.analyze(file, file, file.toUri().toString());
        assertTrue(diagnostics.isEmpty(), () -> "Expected no diagnostics, got: " + describe(diagnostics));
    }

    @Test
    void malformedXmlIsReported() {
        Path file = FIXTURES_ROOT.resolve("invalid/MalformedMapper.xml");
        List<Diagnostic> diagnostics = analyzer.analyze(file, file, file.toUri().toString());
        assertEquals(1, diagnostics.size());
        assertEquals(RuleIds.MALFORMED_XML, diagnostics.get(0).getRuleId());
        assertEquals(Severity.ERROR, diagnostics.get(0).getSeverity());
    }

    @Test
    void missingNamespaceIsReported() {
        Path file = FIXTURES_ROOT.resolve("invalid/MissingNamespaceMapper.xml");
        List<Diagnostic> diagnostics = analyzer.analyze(file, file, file.toUri().toString());
        assertEquals(1, diagnostics.size());
        assertEquals(RuleIds.MISSING_NAMESPACE, diagnostics.get(0).getRuleId());
    }

    @Test
    void duplicateStatementIdIsReported() {
        Path file = FIXTURES_ROOT.resolve("invalid/DuplicateIdMapper.xml");
        List<Diagnostic> diagnostics = analyzer.analyze(file, file, file.toUri().toString());
        assertEquals(1, diagnostics.size());
        assertEquals(RuleIds.DUPLICATE_STATEMENT_ID, diagnostics.get(0).getRuleId());
        assertEquals(9, diagnostics.get(0).getRange().getStart().getLine());
    }

    @Test
    void chooseMisuseIsReported() {
        Path file = FIXTURES_ROOT.resolve("invalid/ChooseMisuseMapper.xml");
        List<Diagnostic> diagnostics = analyzer.analyze(file, file, file.toUri().toString());

        long duplicateOtherwise = diagnostics.stream()
                .filter(d -> d.getRuleId().equals(RuleIds.DUPLICATE_OTHERWISE)).count();
        long otherwiseOutsideChoose = diagnostics.stream()
                .filter(d -> d.getRuleId().equals(RuleIds.OTHERWISE_OUTSIDE_CHOOSE)).count();
        long whenOutsideChoose = diagnostics.stream()
                .filter(d -> d.getRuleId().equals(RuleIds.WHEN_OUTSIDE_CHOOSE)).count();

        assertEquals(1, duplicateOtherwise, () -> describe(diagnostics));
        assertEquals(1, otherwiseOutsideChoose, () -> describe(diagnostics));
        assertEquals(1, whenOutsideChoose, () -> describe(diagnostics));
        assertEquals(3, diagnostics.size(), () -> describe(diagnostics));
    }

    private static String describe(List<Diagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : diagnostics) {
            sb.append(d.getRuleId()).append('@').append(d.getRange().getStart().getLine()).append(' ');
        }
        return sb.toString();
    }

    private static Path findFixturesRoot() {
        // The Gradle project lives at <repo>/analyzer; fixtures live at <repo>/fixtures.
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path fixtures = candidate.resolve("fixtures");
            if (java.nio.file.Files.isDirectory(fixtures)) {
                return fixtures;
            }
            candidate = candidate.getParent();
            if (candidate == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate the 'fixtures' directory from " + Path.of("").toAbsolutePath());
    }
}
