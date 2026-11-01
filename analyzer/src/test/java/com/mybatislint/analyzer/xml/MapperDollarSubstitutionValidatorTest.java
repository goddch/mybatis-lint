package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 値を書く場所の ${...}(SQL インジェクション)。 */
class MapperDollarSubstitutionValidatorTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void writeMapperInterface() throws IOException {
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import java.util.List;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    List<User> search(@Param("name") String name, @Param("status") Integer status,
                                      @Param("active") boolean active, @Param("sort") String sort,
                                      @Param("ids") String ids, @Param("table") String table);
                }
                """);
    }

    @Test
    void reportsDollarSubstitutionWhereAValueIsWritten() throws IOException {
        Path file = writeMapper("""
                <select id="search" resultType="map">
                    SELECT * FROM users
                     WHERE name = ${name}
                       AND note LIKE '%${name}%'
                       AND id IN (${ids})
                    <if test="name != null and name.length() > 0">AND code &lt;&gt; ${name}</if>
                </select>
                """);

        List<Diagnostic> diagnostics = dollarDiagnostics();
        assertEquals(4, diagnostics.size(), diagnostics::toString);
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));

        Diagnostic value = diagnostics.get(0);
        String line = Files.readAllLines(file).get(4);
        assertEquals(5, value.getRange().getStart().getLine());
        assertEquals(line.indexOf("${name}") + 1, value.getRange().getStart().getCharacter());
        assertEquals(line.indexOf("${name}") + 1 + "${name}".length(), value.getRange().getEnd().getCharacter());
        assertEquals("#{name}", value.getSuggestion());
        assertTrue(value.getMessage().contains("SQL injection"), value.getMessage());

        Diagnostic literal = diagnostics.get(1);
        assertNull(literal.getSuggestion());
        assertTrue(literal.getMessage().contains("'%' || #{name} || '%'"), literal.getMessage());
        assertTrue(diagnostics.get(2).getMessage().contains("<foreach>"), diagnostics.get(2).getMessage());
        assertEquals("#{name}", diagnostics.get(3).getSuggestion());
    }

    @Test
    void acceptsPlacesWhereOnlyDollarSubstitutionWorksAndSafeValues() throws IOException {
        writeMapper("""
                <select id="search" resultType="map">
                    SELECT * FROM ${table} t
                     WHERE t.status = ${status}
                       AND t.active = ${active}
                       AND t.kind = ${@com.example.Kinds@DEFAULT}
                       AND t.name = #{name}
                       -- AND t.name = ${name}
                     ORDER BY ${sort}
                </select>
                <sql id="tableName">users_${suffix}</sql>
                """);

        assertEquals(List.of(), dollarDiagnostics());
    }

    @Test
    void ignoresDollarSignsInsideAttributes() throws IOException {
        // test 属性の中は OGNL で、SQL ではない。属性値の中の > でタグが終わったとも読まない。
        writeMapper("""
                <select id="search" resultType="map">
                    SELECT * FROM users
                    <if test="status > 0 and name == '${x}'">WHERE 1 = 1</if>
                     ORDER BY ${sort}
                </select>
                """);

        assertEquals(List.of(), dollarDiagnostics());
    }

    private List<Diagnostic> dollarDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.UNSAFE_DOLLAR_SUBSTITUTION.equals(d.getRuleId()))
                .toList();
    }

    private Path writeMapper(String statements) throws IOException {
        return write("src/main/resources/com/example/UserMapper.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"com.example.UserMapper\">\n"
                        + statements + "</mapper>\n");
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
