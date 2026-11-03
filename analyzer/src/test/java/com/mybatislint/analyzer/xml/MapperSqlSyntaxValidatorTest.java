package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import com.mybatislint.analyzer.testutil.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperSqlSyntaxValidatorTest {

    private static final String TYPO_MAPPER = """
            <mapper namespace="a.UserMapper">
              <select id="typo" resultType="map">SELECT ID FORM USERS</select>
            </mapper>
            """;

    @Test
    void reportsSyntaxErrorsInTheOracleFixture() {
        Path workspace = TestFixtures.fixturesRoot().resolve("sql-syntax-validation/oracle");
        List<Diagnostic> diagnostics = syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics());

        assertEquals(List.of(8, 13, 21, 27), diagnostics.stream().map(d -> d.getRange().getStart().getLine()).toList(),
                diagnostics::toString);
        for (Diagnostic diagnostic : diagnostics) {
            assertTrue(diagnostic.getFile().toString().endsWith("SyntaxInvalidMapper.xml"), diagnostic::toString);
            assertEquals(Severity.WARNING, diagnostic.getSeverity());
            assertTrue(diagnostic.getMessage().contains("checked as Oracle SQL"), diagnostic::toString);
        }
    }

    @Test
    void pointsAtTheTokenInTheXml() throws IOException {
        Path workspace = TestFixtures.fixturesRoot().resolve("sql-syntax-validation/oracle");
        Diagnostic missingComma = syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics()).get(1);
        String line = Files.readAllLines(workspace.resolve("src/main/resources/com/example/SyntaxInvalidMapper.xml"))
                .get(missingComma.getRange().getStart().getLine() - 1);
        int start = line.indexOf("MAIL") + 1;
        assertEquals(start, missingComma.getRange().getStart().getCharacter());
        assertEquals(start + "MAIL".length(), missingComma.getRange().getEnd().getCharacter());
    }

    @Test
    void usesThePostgresqlGrammarForAPostgresqlProject() {
        Path workspace = TestFixtures.fixturesRoot().resolve("sql-syntax-validation/postgresql");
        List<Diagnostic> diagnostics = syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics());
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        assertTrue(diagnostics.get(0).getMessage().contains("checked as PostgreSQL SQL"));
    }

    @Test
    void doesNotCheckWhenTheDatabaseIsUnknown(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("UserMapper.xml"), TYPO_MAPPER);
        assertEquals(List.of(), syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void doesNotCheckWhenMoreThanOneDatabaseIsFound(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("UserMapper.xml"), TYPO_MAPPER);
        Files.writeString(workspace.resolve("application.properties"), "spring.datasource.url=jdbc:oracle:thin:@db\n");
        Files.writeString(workspace.resolve("build.gradle"), "runtimeOnly 'org.postgresql:postgresql:42.7.4'\n");
        assertEquals(List.of(), syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void theSettingOverridesDetectionAndCanTurnTheCheckOff(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("UserMapper.xml"), TYPO_MAPPER);
        assertEquals(1, syntaxErrors(new AnalyzerCli().withSqlDialect("mysql").run(workspace).getDiagnostics()).size());

        Files.writeString(workspace.resolve("application.yml"), "url: jdbc:mysql://localhost/app\n");
        assertEquals(1, syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics()).size());
        assertEquals(List.of(), syntaxErrors(new AnalyzerCli().withSqlDialect("off").run(workspace).getDiagnostics()));
    }

    @Test
    void checksStatementsWithAKnownDatabaseIdEvenWithoutDetection(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("UserMapper.xml"), """
                <mapper namespace="a.UserMapper">
                  <select id="pg" databaseId="postgresql" resultType="map">SELECT ID FORM USERS</select>
                  <select id="other" databaseId="h2" resultType="map">SELECT ID FORM USERS</select>
                </mapper>
                """);
        List<Diagnostic> diagnostics = syntaxErrors(new AnalyzerCli().run(workspace).getDiagnostics());
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        assertTrue(diagnostics.get(0).getMessage().contains("PostgreSQL"));
    }

    @Test
    void reportsErrorsInIncludedFragmentsAtTheFragment(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("UserMapper.xml"), """
                <mapper namespace="a.UserMapper">
                  <sql id="cols">ID,, NAME</sql>
                  <select id="s" resultType="map">SELECT <include refid="cols"/> FROM USERS</select>
                </mapper>
                """);
        List<Diagnostic> diagnostics = syntaxErrors(new AnalyzerCli().withSqlDialect("oracle").run(workspace).getDiagnostics());
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        assertEquals(2, diagnostics.get(0).getRange().getStart().getLine());
    }

    @Test
    void canBeSilencedLikeAnyOtherRule(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("UserMapper.xml"), """
                <mapper namespace="a.UserMapper">
                  <!-- mybatis-lint-disable-next-line sql-syntax-error -->
                  <select id="typo" resultType="map">SELECT ID FORM USERS</select>
                </mapper>
                """);
        assertEquals(List.of(), syntaxErrors(new AnalyzerCli().withSqlDialect("oracle").run(workspace).getDiagnostics()));
    }

    private static List<Diagnostic> syntaxErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().filter(d -> RuleIds.SQL_SYNTAX_ERROR.equals(d.getRuleId())).toList();
    }
}
