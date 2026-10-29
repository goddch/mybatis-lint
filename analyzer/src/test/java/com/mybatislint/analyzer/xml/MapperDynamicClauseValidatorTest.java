package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** <where> / <set> を使わない WHERE / SET と <if> の組み合わせで、SQL が壊れるもの。 */
class MapperDynamicClauseValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void reportsAnAndThatAlwaysFollowsWhere() throws IOException {
        Path file = writeMapper("""
                <select id="find" resultType="map">
                    SELECT * FROM users
                     WHERE
                    <if test="name != null">AND name = #{name}</if>
                </select>
                """);

        List<Diagnostic> diagnostics = clauseDiagnostics();
        assertEquals(List.of("dangling-sql-joiner:ERROR", "empty-dynamic-clause:WARNING"), summary(diagnostics));
        String line = Files.readAllLines(file).get(5);
        Diagnostic error = diagnostics.get(0);
        assertEquals(6, error.getRange().getStart().getLine());
        assertEquals(line.indexOf("AND") + 1, error.getRange().getStart().getCharacter());
        assertTrue(error.getMessage().contains("'WHERE AND/OR ...'"), error.getMessage());
        assertTrue(error.getMessage().contains("<where>"), error.getMessage());
        Diagnostic empty = diagnostics.get(1);
        assertEquals(Files.readAllLines(file).get(4).indexOf("WHERE") + 1, empty.getRange().getStart().getCharacter());
    }

    @Test
    void warnsWhenAnAndFollowsOnlyConditionalConditions() throws IOException {
        writeMapper("""
                <select id="find" resultType="map">
                    SELECT * FROM users WHERE
                    <if test="id != null">id = #{id}</if>
                    <if test="name != null">AND name = #{name}</if>
                    ORDER BY id
                </select>
                """);

        assertEquals(List.of("dangling-sql-joiner:WARNING", "empty-dynamic-clause:WARNING"), summary(clauseDiagnostics()));
    }

    @Test
    void warnsAboutATrailingAndBeforeConditionalConditions() throws IOException {
        writeMapper("""
                <select id="find" resultType="map">
                    SELECT * FROM users WHERE status = 1 AND
                    <if test="name != null">name = #{name}</if>
                </select>
                """);

        List<Diagnostic> diagnostics = clauseDiagnostics();
        assertEquals(List.of("dangling-sql-joiner:WARNING"), summary(diagnostics));
        assertTrue(diagnostics.get(0).getMessage().contains("move AND/OR to the start"));
    }

    @Test
    void reportsTrailingCommasInSet() throws IOException {
        writeMapper("""
                <update id="update">
                    UPDATE users SET
                    <if test="name != null">name = #{name},</if>
                    <if test="mail != null">mail = #{mail},</if>
                     WHERE id = #{id}
                </update>
                <update id="updateWithFixedColumn">
                    UPDATE users SET updated_at = SYSDATE,
                    <if test="name != null">name = #{name}</if>
                     WHERE id = #{id}
                </update>
                """);

        assertEquals(List.of("dangling-sql-joiner:ERROR", "dangling-sql-joiner:WARNING", "empty-dynamic-clause:WARNING",
                "dangling-sql-joiner:WARNING"), summary(clauseDiagnostics()));
    }

    @Test
    void reportsALeadingCommaThatCanFollowSet() throws IOException {
        writeMapper("""
                <update id="update">
                    UPDATE users SET
                    <if test="name != null">name = #{name}</if>
                    <if test="mail != null">, mail = #{mail}</if>
                    , updated_at = SYSDATE
                     WHERE id = #{id}
                </update>
                """);

        assertEquals(List.of("dangling-sql-joiner:WARNING", "dangling-sql-joiner:WARNING"), summary(clauseDiagnostics()));
    }

    @Test
    void acceptsTheUsualSafeWritingStyles() throws IOException {
        writeMapper("""
                <select id="withWhereTag" resultType="map">
                    SELECT * FROM users
                    <where>
                        <if test="id != null">AND id = #{id}</if>
                        <if test="name != null">AND name = #{name}</if>
                    </where>
                </select>
                <select id="withFixedCondition" resultType="map">
                    SELECT * FROM users WHERE 1 = 1
                    <if test="name != null">AND name = #{name}</if>
                    ORDER BY id
                </select>
                <select id="withChooseOtherwise" resultType="map">
                    SELECT * FROM users WHERE
                    <choose>
                        <when test="id != null">id = #{id}</when>
                        <otherwise>status = 1</otherwise>
                    </choose>
                    <if test="name != null">AND name = #{name}</if>
                </select>
                <select id="withSubquery" resultType="map">
                    SELECT * FROM users u
                     WHERE u.id IN (SELECT g.user_id FROM groups g WHERE g.kind = 'A')
                    <if test="name != null">AND u.name = #{name}</if>
                </select>
                <update id="withSetTag">
                    UPDATE users
                    <set>
                        <if test="name != null">name = #{name},</if>
                        <if test="mail != null">mail = #{mail},</if>
                    </set>
                     WHERE id = #{id}
                </update>
                <update id="withFixedLastColumn">
                    UPDATE users SET
                    <if test="name != null">name = #{name},</if>
                    updated_at = SYSDATE
                     WHERE id = #{id}
                </update>
                <update id="withLeadingCommas">
                    UPDATE users SET updated_at = SYSDATE
                    <if test="name != null">, name = #{name}</if>
                     WHERE id = #{id}
                </update>
                """);

        assertEquals(List.of(), clauseDiagnostics());
    }

    @Test
    void skipsWhatItCannotFollow() throws IOException {
        // <include> の中身や、<sql> の続きは見えないので、その先は判断しない。
        writeMapper("""
                <sql id="conditions">
                    WHERE
                    <if test="name != null">name = #{name}</if>
                </sql>
                <sql id="more">AND 1 = 1</sql>
                <select id="withInclude" resultType="map">
                    SELECT * FROM users WHERE
                    <include refid="more"/>
                    <if test="name != null">AND name = #{name}</if>
                </select>
                <select id="withNestedIf" resultType="map">
                    SELECT * FROM users WHERE status = 1 AND
                    <if test="name != null"><if test="mail != null">mail = #{mail}</if></if>
                </select>
                """);

        List<Diagnostic> diagnostics = clauseDiagnostics();
        // withNestedIf の末尾の AND は、<if> の中身にかかわらず <if> が成り立たないと残る。
        assertEquals(List.of("dangling-sql-joiner:WARNING"), summary(diagnostics));
    }

    @Test
    void ignoresKeywordsInCommentsAndStrings() throws IOException {
        writeMapper("""
                <select id="find" resultType="map">
                    SELECT * FROM users WHERE kind = 'WHERE' /* WHERE */ -- WHERE
                    <if test="name != null">AND name = #{name}</if>
                </select>
                """);

        assertEquals(List.of(), clauseDiagnostics());
    }

    private static List<String> summary(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> d.getRuleId().replace("mybatis-lint/", "") + ":" + d.getSeverity().name())
                .toList();
    }

    private List<Diagnostic> clauseDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.DANGLING_SQL_JOINER.equals(d.getRuleId())
                        || RuleIds.EMPTY_DYNAMIC_CLAUSE.equals(d.getRuleId()))
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
