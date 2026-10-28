package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文字列の中の #{...}、1 文字の '...' との比較、数値と '' の比較を確かめる。
 */
class MapperSqlPitfallTest {

    @TempDir
    Path workspace;

    @Test
    void reportsParametersInsideStringLiteralsAtTheParameter() throws IOException {
        Path xml = writeMapper("""
                <select id="search">
                    SELECT * FROM users -- the user's name
                    WHERE NAME LIKE '%#{name}%' AND NOTE = 'it''s #{note}'
                </select>
                <sql id="fragment">AND CODE = '#{code}'</sql>
                """);

        List<Diagnostic> errors = byRule(RuleIds.PARAMETER_IN_STRING_LITERAL);
        assertEquals(List.of("#{name}", "#{note}", "#{code}"), errors.stream()
                .map(d -> d.getMessage().replaceAll("^Parameter (#\\{[^}]*}).*", "$1")).toList(), errors::toString);
        assertTrue(errors.stream().allMatch(d -> d.getSeverity() == Severity.ERROR));
        String line = Files.readAllLines(xml).get(4);
        assertEquals(line.indexOf("#{name}") + 1, errors.get(0).getRange().getStart().getCharacter());
        assertEquals(line.indexOf("#{name}") + 1 + "#{name}".length(), errors.get(0).getRange().getEnd().getCharacter());
    }

    @Test
    void acceptsParametersOutsideLiteralsAndSkipsUncertainQuoting() throws IOException {
        writeMapper("""
                <select id="concat">SELECT * FROM t WHERE NAME LIKE '%' || #{name} || '%' AND X = '${raw}'</select>
                <select id="sameOutsideFirst">SELECT #{name}, '#{name}' FROM t /* it's */</select>
                <select id="qQuote">SELECT q'[it's #{name}]' FROM dual</select>
                <select id="unbalanced">SELECT 'a FROM t WHERE x = #{name}</select>
                """);

        // 外にある #{name} を飛ばし、2 つ目(文字列の中)だけを指す。
        List<Diagnostic> errors = byRule(RuleIds.PARAMETER_IN_STRING_LITERAL);
        assertEquals(1, errors.size(), errors::toString);
        assertEquals(4, errors.get(0).getRange().getStart().getLine());
    }

    @Test
    void reportsSingleCharacterLiteralComparisons() throws IOException {
        write("src/main/java/com/example/Condition.java", """
                package com.example;
                public class Condition { private String type; private char grade; private Character rank; }
                """);
        writeMapper("""
                int search(Condition condition);
                int byKind(@org.apache.ibatis.annotations.Param("kind") String kind,
                           @org.apache.ibatis.annotations.Param("letter") char letter);
                """, """
                <select id="search">
                    <if test="type == 'A'"/>
                    <if test="'B' != type"/>
                    <if test="type eq 'C'"/>
                    <if test="type == 'AB'"/>
                    <if test="type == 'D'.toString()"/>
                    <if test='type == "E"'/>
                    <if test="grade == 'S'"/>
                    <if test="rank == 'T'"/>
                </select>
                <select id="byKind">
                    <if test="kind.equals('Z')"/>
                    <if test="letter == 'L'"/>
                </select>
                <select id="noMethod"><if test="anything == 'Q'"/></select>
                """);

        List<Diagnostic> warnings = byRule(RuleIds.SINGLE_CHAR_LITERAL_COMPARISON);
        assertEquals(List.of("'A'", "'B'", "'C'", "'Z'", "'Q'"), warnings.stream()
                .map(d -> d.getMessage().replaceAll("^OGNL treats ('.') .*", "$1")).toList(), warnings::toString);
        assertTrue(warnings.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
    }

    @Test
    void reportsEmptyStringComparisonsOnlyForNumericOperands() throws IOException {
        write("src/main/java/com/example/Condition.java", """
                package com.example;
                public class Condition { private String name; private Integer status; private long count; }
                """);
        writeMapper("""
                int search(Condition condition);
                int byStatus(@org.apache.ibatis.annotations.Param("status") Integer status,
                             @org.apache.ibatis.annotations.Param("name") String name);
                int single(java.math.BigDecimal amount);
                """, """
                <select id="search">
                    <if test="status != null and status != ''"/>
                    <if test="'' == count"/>
                    <if test="name != ''"/>
                    <if test="unknown != ''"/>
                </select>
                <select id="byStatus">
                    <if test="status != ''"/>
                    <if test="name != ''"/>
                    <foreach collection="list" item="status"><if test="status != ''"/></foreach>
                </select>
                <select id="single"><if test="amount != ''"/></select>
                """);

        List<Diagnostic> warnings = byRule(RuleIds.NUMERIC_EMPTY_STRING_COMPARISON);
        // byStatus の status は <foreach> の item と同じ名前なので、引数の型と決めつけない。
        assertEquals(3, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).getMessage().contains("status != ''"));
        assertTrue(warnings.get(1).getMessage().contains("'' == count"));
        assertTrue(warnings.get(2).getMessage().contains("amount != ''"));
    }

    private List<Diagnostic> byRule(String ruleId) {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> ruleId.equals(d.getRuleId()))
                .toList();
    }

    private Path writeMapper(String statements) throws IOException {
        return write("src/main/resources/com/example/UserMapper.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"com.example.UserMapper\">\n"
                        + statements + "</mapper>\n");
    }

    private Path writeMapper(String methods, String statements) throws IOException {
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {
                %s}
                """.formatted(methods));
        return writeMapper(statements);
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
