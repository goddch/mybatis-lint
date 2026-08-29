package com.mybatislint.analyzer.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerCliTest {

    private static final Path FIXTURES_ROOT = findFixturesRoot();

    @Test
    void reportsNoErrorsForValidFixtures() {
        AnalyzerCli.Result result = new AnalyzerCli().run(FIXTURES_ROOT.resolve("valid"));
        assertTrue(result.getDiagnostics().isEmpty());
        assertTrue(!result.hasErrors());
    }

    @Test
    void reportsErrorsForInvalidFixtures() {
        AnalyzerCli.Result result = new AnalyzerCli().run(FIXTURES_ROOT.resolve("invalid"));
        assertTrue(result.hasErrors());
        // one diagnostic per invalid fixture file, at minimum
        assertTrue(result.getDiagnostics().size() >= 4);
    }

    private static Path findFixturesRoot() {
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
        throw new IllegalStateException("Could not locate the 'fixtures' directory");
    }
}
