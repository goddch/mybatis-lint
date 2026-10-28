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

/** resultMap の column が、それを使う select の SELECT 句にないものを見つける。 */
class MapperResultMapColumnValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void reportsColumnsMissingFromTheOnlySelect() throws IOException {
        Path file = write("UserMapper.xml", """
                <mapper namespace="example.UserMapper">
                    <resultMap id="userMap" type="map">
                        <id column="USER_ID" property="userId"/>
                        <result column="user_name" property="userName"/>
                        <result column="USER_NMAE" property="nickname"/>
                        <result column="&quot;Mail&quot;" property="mail"/>
                    </resultMap>
                    <select id="find" resultMap="userMap">
                        SELECT /*+ FIRST_ROWS */ u.USER_ID, u.USER_NAME, u.EMAIL AS "Mail"
                          FROM users u
                        <where><if test="id != null">u.USER_ID = #{id}</if></where>
                    </select>
                </mapper>
                """);

        List<Diagnostic> warnings = columnDiagnostics();
        assertEquals(1, warnings.size(), warnings::toString);
        Diagnostic warning = warnings.get(0);
        assertEquals(Severity.WARNING, warning.getSeverity());
        assertEquals(5, warning.getRange().getStart().getLine());
        String line = Files.readAllLines(file).get(4);
        assertEquals(line.indexOf("USER_NMAE") + 1, warning.getRange().getStart().getCharacter());
        assertTrue(warning.getMessage().contains("'USER_NMAE'"));
        assertTrue(warning.getMessage().contains("'find'"));
        assertTrue(warning.getMessage().contains("'nickname'"));
    }

    @Test
    void skipsSharedOrComplexResultMapsAndUndeterminedSelectLists() throws IOException {
        write("A.xml", """
                <mapper namespace="example.A">
                    <resultMap id="shared" type="map"><result column="BIG" property="big"/></resultMap>
                    <select id="list" resultMap="shared">SELECT ID FROM t</select>
                    <select id="detail" resultMap="shared">SELECT ID, BIG FROM t</select>

                    <resultMap id="usedByOtherFile" type="map"><result column="X" property="x"/></resultMap>
                    <select id="one" resultMap="usedByOtherFile">SELECT ID FROM t</select>

                    <resultMap id="parent" type="map"><result column="P" property="p"/></resultMap>
                    <resultMap id="child" type="map" extends="parent"><result column="C" property="c"/></resultMap>
                    <select id="byParent" resultMap="parent">SELECT ID FROM t</select>
                    <select id="byChild" resultMap="child">SELECT ID FROM t</select>

                    <resultMap id="nested" type="map">
                        <result column="N" property="n"/>
                        <association property="a" javaType="map"><result column="A" property="v"/></association>
                    </resultMap>
                    <select id="byNested" resultMap="nested">SELECT ID FROM t</select>

                    <resultMap id="star" type="map"><result column="S" property="s"/></resultMap>
                    <select id="byStar" resultMap="star">SELECT * FROM t</select>
                    <resultMap id="expr" type="map"><result column="E" property="e"/></resultMap>
                    <select id="byExpr" resultMap="expr">SELECT COUNT(*) FROM t</select>
                    <resultMap id="include" type="map"><result column="I" property="i"/></resultMap>
                    <select id="byInclude" resultMap="include">SELECT <include refid="cols"/> FROM t</select>
                    <resultMap id="multi" type="map"><result column="M" property="m"/></resultMap>
                    <select id="byMulti" resultMap="multi,shared">SELECT ID FROM t</select>
                    <resultMap id="composite" type="map">
                        <result column="{a=A,b=B}" property="c"/><result column="${col}" property="d"/>
                    </resultMap>
                    <select id="byComposite" resultMap="composite">SELECT ID FROM t</select>
                    <sql id="cols">ID</sql>
                </mapper>
                """);
        write("B.xml", """
                <mapper namespace="example.B">
                    <select id="remote" resultMap="example.A.usedByOtherFile">SELECT X FROM t</select>
                </mapper>
                """);

        assertTrue(columnDiagnostics().isEmpty(), () -> columnDiagnostics().toString());
    }

    @Test
    void skipsOnlyTheNamespaceOfABrokenMapperXml() throws IOException {
        write("A.xml", """
                <mapper namespace="example.A">
                    <resultMap id="m" type="map"><result column="MISSING" property="x"/></resultMap>
                    <select id="find" resultMap="m">SELECT ID FROM t</select>
                </mapper>
                """);
        write("A2.xml", "<mapper namespace=\"example.A\"><select id=\"other\" resultMap=\"m\">");
        write("B.xml", """
                <mapper namespace="example.B">
                    <resultMap id="m" type="map"><result column="MISSING" property="x"/></resultMap>
                    <select id="find" resultMap="m">SELECT ID FROM t</select>
                </mapper>
                """);

        List<Diagnostic> warnings = columnDiagnostics();
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).getFile().toString().endsWith("B.xml"));
    }

    private List<Diagnostic> columnDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.RESULT_MAP_COLUMN_NOT_SELECTED.equals(d.getRuleId()))
                .toList();
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, contents);
        return file;
    }
}
