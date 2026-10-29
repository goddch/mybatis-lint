package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosticFilterTest {

    // 3 行目の <when> と 6 行目の <when> が when-outside-choose になる。
    private static final String TWO_WHENS = """
            <mapper namespace="com.example.SampleMapper">
              <select id="a">SELECT 1 FROM dual
                <where><when test="true">AND 1 = 1</when></where>
              </select>
              <select id="b">SELECT 1 FROM dual
                <where><when test="true">AND 1 = 1</when></where>
              </select>
            </mapper>
            """;

    @Test
    void reportsEverythingWithoutDirectivesOrDisabledRules(@TempDir Path workspace) throws IOException {
        write(workspace, TWO_WHENS);
        assertEquals(List.of(3, 6), lines(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void disabledRulesAcceptIdsWithOrWithoutPrefix(@TempDir Path workspace) throws IOException {
        write(workspace, TWO_WHENS);
        for (String rule : List.of("when-outside-choose", "mybatis-lint/when-outside-choose", " when-outside-choose ")) {
            List<Diagnostic> diagnostics = new AnalyzerCli(
                    com.mybatislint.analyzer.discovery.ExcludePatterns.none(), List.of(rule))
                    .run(workspace).getDiagnostics();
            assertEquals(List.of(), lines(diagnostics), rule);
        }
    }

    @Test
    void disableNextLineSuppressesOnlyTheFollowingLine(@TempDir Path workspace) throws IOException {
        write(workspace, """
                <mapper namespace="com.example.SampleMapper">
                  <select id="a">SELECT 1 FROM dual
                    <!-- mybatis-lint-disable-next-line when-outside-choose -->
                    <where><when test="true">AND 1 = 1</when></where>
                  </select>
                  <select id="b">SELECT 1 FROM dual
                    <where><when test="true">AND 1 = 1</when></where>
                  </select>
                </mapper>
                """);
        assertEquals(List.of(7), lines(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void directiveWithoutRulesSuppressesAllRules(@TempDir Path workspace) throws IOException {
        write(workspace, """
                <mapper namespace="com.example.SampleMapper">
                  <select id="a">SELECT 1 FROM dual
                    <!--mybatis-lint-disable-next-line-->
                    <where><when test="true">AND 1 = 1</when></where>
                  </select>
                </mapper>
                """);
        assertEquals(List.of(), lines(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void directiveForOtherRuleDoesNotSuppress(@TempDir Path workspace) throws IOException {
        write(workspace, """
                <mapper namespace="com.example.SampleMapper">
                  <select id="a">SELECT 1 FROM dual
                    <!-- mybatis-lint-disable-next-line undefined-parameter, duplicate-otherwise -->
                    <where><when test="true">AND 1 = 1</when></where>
                  </select>
                </mapper>
                """);
        assertEquals(List.of(4), lines(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void disableFileSuppressesTheWholeFile(@TempDir Path workspace) throws IOException {
        write(workspace, "<!-- mybatis-lint-disable-file mybatis-lint/when-outside-choose -->\n" + TWO_WHENS);
        assertEquals(List.of(), lines(new AnalyzerCli().run(workspace).getDiagnostics()));
    }

    @Test
    void javaLineCommentsWorkToo() {
        DiagnosticFilter.Suppressions suppressions = DiagnosticFilter.Suppressions.parse("""
                interface SampleMapper {
                    // mybatis-lint-disable-next-line missing-mapper-statement
                    void a();
                    /* mybatis-lint-disable-next-line */ void b();
                    void c();
                }
                """);
        assertEquals(true, suppressions.suppresses(3, RuleIds.MISSING_MAPPER_STATEMENT));
        assertEquals(false, suppressions.suppresses(3, RuleIds.UNDEFINED_PARAMETER));
        assertEquals(true, suppressions.suppresses(5, RuleIds.UNDEFINED_PARAMETER));
        assertEquals(false, suppressions.suppresses(4, RuleIds.UNDEFINED_PARAMETER));
    }

    @Test
    void reportsUnknownRuleIds() {
        assertEquals(List.of("when-outside-chose"),
                DiagnosticFilter.unknownRuleIds(List.of("when-outside-choose", "when-outside-chose", " ")));
    }

    private static void write(Path workspace, String xml) throws IOException {
        Files.writeString(workspace.resolve("SampleMapper.xml"), xml);
    }

    private static List<Integer> lines(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(d -> RuleIds.WHEN_OUTSIDE_CHOOSE.equals(d.getRuleId()))
                .map(d -> d.getRange().getStart().getLine())
                .toList();
    }
}
