package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.testutil.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerCliTest {

    private static final Path FIXTURES_ROOT = TestFixtures.fixturesRoot();

    @Test
    void reportsNoErrorsForValidFixtures() {
        AnalyzerCli.Result result = new AnalyzerCli().run(FIXTURES_ROOT.resolve("valid"));
        assertTrue(result.getDiagnostics().isEmpty());
        assertTrue(!result.hasErrors());
        assertEquals(1, result.getMapperFileCount());
    }

    @Test
    void reportsErrorsForInvalidFixtures() {
        AnalyzerCli.Result result = new AnalyzerCli().run(FIXTURES_ROOT.resolve("invalid"));
        assertTrue(result.hasErrors());
        // one diagnostic per invalid fixture file, at minimum
        assertTrue(result.getDiagnostics().size() >= 4);
        // MalformedMapper.xml はルート要素を判定できないので mapper として数えない
        assertEquals(3, result.getMapperFileCount());
    }

    @Test
    void doesNotCountNonMapperXmlFiles(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);

        assertTrue(result.getDiagnostics().isEmpty());
        assertEquals(0, result.getMapperFileCount());
    }
}
