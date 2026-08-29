package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.testutil.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerCliTest {

    private static final Path FIXTURES_ROOT = TestFixtures.fixturesRoot();

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
}
