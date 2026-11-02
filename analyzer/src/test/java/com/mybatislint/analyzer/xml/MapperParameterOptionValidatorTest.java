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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** jdbcType / mode の値と、#{...} のオプション名。 */
class MapperParameterOptionValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void reportsJdbcTypesAndModesMyBatisCannotRead() throws IOException {
        Path file = writeMapper("""
                <resultMap id="userMap" type="map">
                    <result property="name" column="NAME" jdbcType="VARCHR"/>
                </resultMap>
                <select id="find" resultMap="userMap">
                    SELECT * FROM users WHERE name = #{name,jdbcType=varchar} AND id = #{id:NUMERC}
                </select>
                <select id="call" statementType="CALLABLE">
                    {call proc(#{out,mode=OUTPUT,jdbcType=CURSOR})}
                </select>
                """);

        List<Diagnostic> diagnostics = optionDiagnostics();
        // OUTPUT は OUT にも INOUT にも同じだけ近いので、候補を出さない。
        assertEquals(Arrays.asList("VARCHAR", "VARCHAR", "NUMERIC", null),
                diagnostics.stream().map(Diagnostic::getSuggestion).toList());
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == Severity.ERROR
                && RuleIds.INVALID_JDBC_TYPE.equals(d.getRuleId())), diagnostics::toString);
        String line = Files.readAllLines(file).get(6);
        Diagnostic lowercase = diagnostics.get(1);
        assertEquals(7, lowercase.getRange().getStart().getLine());
        assertEquals(line.indexOf("varchar") + 1, lowercase.getRange().getStart().getCharacter());
        assertEquals(line.indexOf("varchar") + 1 + "varchar".length(), lowercase.getRange().getEnd().getCharacter());
        assertTrue(lowercase.getMessage().contains("case-sensitive"), lowercase.getMessage());
    }

    @Test
    void reportsOptionsMyBatisDoesNotAccept() throws IOException {
        writeMapper("""
                <select id="find" resultType="map">
                    SELECT * FROM users WHERE name = #{name, jdbctype=VARCHAR} AND id = #{id,NUMERIC}
                </select>
                """);

        List<Diagnostic> diagnostics = optionDiagnostics();
        assertEquals(2, diagnostics.size(), diagnostics::toString);
        assertEquals(RuleIds.UNKNOWN_PARAMETER_OPTION, diagnostics.get(0).getRuleId());
        assertEquals("jdbcType", diagnostics.get(0).getSuggestion());
        assertNull(diagnostics.get(1).getSuggestion());
    }

    @Test
    void acceptsValidOptionsAndSkipsWhatItCannotRead() throws IOException {
        writeMapper("""
                <!-- コメントの中は読まない: #{x,jdbcType=nonsense} -->
                <select id="find" resultType="map">
                    SELECT * FROM users
                     WHERE name = #{name, jdbcType = VARCHAR, javaType=string, typeHandler=com.example.Handler}
                       AND id = #{id:NUMERIC} AND amount = #{amount,jdbcType=DECIMAL,numericScale=2}
                       AND code = #{code,jdbcType=${type}} AND x = #{(expression)} AND y = #{y,}
                </select>
                """);

        assertEquals(List.of(), optionDiagnostics());
    }

    private List<Diagnostic> optionDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.INVALID_JDBC_TYPE.equals(d.getRuleId())
                        || RuleIds.UNKNOWN_PARAMETER_OPTION.equals(d.getRuleId()))
                .toList();
    }

    private Path writeMapper(String statements) throws IOException {
        Path file = workspace.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"com.example.UserMapper\">\n"
                + statements + "</mapper>\n");
        return file;
    }
}
