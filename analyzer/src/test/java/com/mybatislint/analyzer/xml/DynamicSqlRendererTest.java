package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.testutil.TestFixtures;
import com.mybatislint.analyzer.xml.DynamicSqlRenderer.Rendered;
import com.mybatislint.analyzer.xml.DynamicSqlRenderer.Variant;
import com.mybatislint.analyzer.xml.MapperXmlTree.Element;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicSqlRendererTest {

    /**
     * SQL プレビュー(vscode-extension/test/sqlPreview.test.ts)も同じ見本と期待値を使う。
     * 片方だけ組み立て方を変えると、どちらかのテストが落ちる。
     */
    @Test
    void buildsTheSameSqlAsTheSqlPreview() throws IOException {
        Path dir = TestFixtures.fixturesRoot().resolve("sql-render");
        String casesXml = Files.readString(dir.resolve("RenderCasesMapper.xml"));
        Element cases = MapperXmlTree.parse(casesXml);
        Element common = MapperXmlTree.parse(Files.readString(dir.resolve("CommonMapper.xml")));
        Map<String, Element> fragments = new HashMap<>();
        for (Element mapper : new Element[] {cases, common}) {
            for (Element child : mapper.childElements()) {
                if (child.name().equals("sql")) {
                    fragments.put(mapper.attribute("namespace") + "." + child.attribute("id"), child);
                }
            }
        }
        DynamicSqlRenderer renderer = new DynamicSqlRenderer((ns, id) -> fragments.get(ns + "." + id));

        Map<String, String> expected = readExpected(dir.resolve("expected.json"));
        assertEquals(4, expected.size());
        for (Element statement : cases.childElements()) {
            if (statement.name().equals("sql")) {
                continue;
            }
            String id = statement.attribute("id");
            Rendered rendered = renderer.render(statement, "example.RenderCasesMapper", Variant.ALL_ON);
            assertEquals(expected.get(id), normalize(rendered.sql()), id);
            assertFalse(rendered.hasDollar(), id);
            assertTrue(rendered.complete(), id);
        }
    }

    @Test
    void allOffDropsEveryIfAndTakesTheLastChooseBranch() {
        Element mapper = MapperXmlTree.parse("""
                <mapper namespace="a.M">
                  <select id="s">SELECT * FROM T
                    <where>
                      <if test="a">AND A = #{a}</if>
                      <choose><when test="b">AND B = 1</when><otherwise>AND B = 2</otherwise></choose>
                    </where>
                  </select>
                </mapper>
                """);
        Rendered rendered = new DynamicSqlRenderer((ns, id) -> null)
                .render(mapper.childElements().get(0), "a.M", Variant.ALL_OFF);
        assertEquals("SELECT * FROM T WHERE B = 2", normalize(rendered.sql()));
    }

    @Test
    void remembersWhereEachSqlCharacterCameFrom() {
        String xml = "<mapper namespace=\"a.M\"><select id=\"s\">SELECT &lt;X&gt;, #{p} FROM T</select></mapper>";
        Rendered rendered = new DynamicSqlRenderer((ns, id) -> null)
                .render(MapperXmlTree.parse(xml).childElements().get(0), "a.M", Variant.ALL_ON);
        assertEquals("SELECT <X>, ? FROM T", rendered.sql());
        // "<" は &lt; の & を、"?" は #{ の # を指す。
        assertEquals(xml.indexOf("&lt;"), rendered.xmlOffset(rendered.sql().indexOf('<')));
        assertEquals(xml.indexOf("#{p}"), rendered.xmlOffset(rendered.sql().indexOf('?')));
        assertEquals(xml.indexOf("FROM"), rendered.xmlOffset(rendered.sql().indexOf("FROM")));
    }

    @Test
    void marksDollarAndMissingIncludes() {
        Element mapper = MapperXmlTree.parse("""
                <mapper namespace="a.M">
                  <select id="dollar">SELECT * FROM ${table}</select>
                  <select id="missing">SELECT <include refid="nowhere"/> FROM T</select>
                </mapper>
                """);
        DynamicSqlRenderer renderer = new DynamicSqlRenderer((ns, id) -> null);
        assertTrue(renderer.render(mapper.childElements().get(0), "a.M", Variant.ALL_ON).hasDollar());
        assertFalse(renderer.render(mapper.childElements().get(1), "a.M", Variant.ALL_ON).complete());
    }

    static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** expected.json は文字列の値だけを持つ平らなオブジェクト。依存を増やさないよう、ここで読む。 */
    private static Map<String, String> readExpected(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(Files.readString(file));
        while (matcher.find()) {
            if (!matcher.group(1).startsWith("_")) {
                values.put(matcher.group(1), matcher.group(2).replace("\\\"", "\""));
            }
        }
        return values;
    }
}
