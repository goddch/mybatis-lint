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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** どこからも使われていない <sql> / <resultMap> と、インターフェースにメソッドがない statement。 */
class MapperUnusedDefinitionValidatorTest {

    private static final Set<String> RULES = Set.of(RuleIds.UNUSED_SQL_FRAGMENT, RuleIds.UNUSED_RESULT_MAP,
            RuleIds.STATEMENT_WITHOUT_METHOD);

    @TempDir
    Path workspace;

    @Test
    void reportsDefinitionsNothingUsesAtTheirId() throws IOException {
        writeJava("UserMapper", """
                package com.example;
                public interface UserMapper { User find(); }
                """);
        Path file = writeXml("UserMapper", """
                <mapper namespace="com.example.UserMapper">
                    <sql id="usedColumns">ID</sql>
                    <sql id="oldColumns">ID, NAME</sql>
                    <resultMap id="userMap" type="map"/>
                    <resultMap id="oldMap" type="map"/>
                    <select id="find" resultMap="userMap">SELECT <include refid="usedColumns"/> FROM users</select>
                    <select id="findOld" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);

        List<Diagnostic> diagnostics = unused();
        assertEquals(List.of("unused-sql-fragment:3", "unused-result-map:5", "statement-without-method:7"),
                summary(diagnostics));
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
        String line = Files.readAllLines(file).get(2);
        assertEquals(line.indexOf("oldColumns") + 1, diagnostics.get(0).getRange().getStart().getCharacter());
        assertTrue(diagnostics.get(2).getMessage().contains("com.example.UserMapper"), diagnostics.get(2).getMessage());
    }

    @Test
    void countsEveryKindOfReferenceAcrossFiles() throws IOException {
        writeJava("UserMapper", """
                package com.example;
                import org.apache.ibatis.annotations.ResultMap;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                    @ResultMap("annotatedMap")
                    @Select("SELECT 1 FROM dual")
                    User findAnnotated();

                    @ResultMap({"arrayMap", "com.example.Common.sharedMap"})
                    @Select("SELECT 1 FROM dual")
                    User findArray();

                    User find();
                }
                """);
        writeXml("Common", """
                <mapper namespace="com.example.Common">
                    <sql id="columns">ID</sql>
                    <resultMap id="sharedMap" type="map"/>
                    <resultMap id="parentMap" type="map"/>
                    <resultMap id="nestedMap" type="map"/>
                </mapper>
                """);
        writeXml("UserMapper", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="annotatedMap" type="map"/>
                    <resultMap id="arrayMap" type="map"/>
                    <resultMap id="childMap" type="map" extends="com.example.Common.parentMap">
                        <collection property="items" resultMap="com.example.Common.nestedMap"/>
                    </resultMap>
                    <select id="find" resultMap="childMap">
                        SELECT <include refid="com.example.Common.columns"/> FROM users
                    </select>
                </mapper>
                """);

        assertEquals(List.of(), unused());
    }

    @Test
    void skipsStatementsThatMayBeCalledAnotherWay() throws IOException {
        // インターフェースがない namespace、親インターフェースのある Mapper、文字列の id で呼ぶ statement。
        writeJava("ChildMapper", """
                package com.example;
                public interface ChildMapper extends BaseMapper<User> { }
                """);
        writeJava("LegacyDao", """
                package com.example;
                public class LegacyDao {
                    Object find(org.apache.ibatis.session.SqlSession session) {
                        return session.selectList("com.example.UserMapper.findByString");
                    }
                }
                """);
        writeJava("UserMapper", """
                package com.example;
                public interface UserMapper { }
                """);
        writeXml("NoInterface", """
                <mapper namespace="com.example.NoInterfaceMapper">
                    <select id="find" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);
        writeXml("ChildMapper", """
                <mapper namespace="com.example.ChildMapper">
                    <select id="selectById" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);
        writeXml("UserMapper", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findByString" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);

        assertEquals(List.of(), unused());
    }

    @Test
    void skipsDefinitionsWhenReferencesCannotBeRead() throws IOException {
        // namespace だけが動的な参照は id で数える。id が動的なら、その種類は判断しない。
        writeXml("Tenant", """
                <mapper namespace="com.example.Tenant">
                    <sql id="tenantColumns">ID</sql>
                    <resultMap id="anyMap" type="map"/>
                    <select id="find" resultMap="${mapName}">
                        SELECT <include refid="${tenant}.tenantColumns"/> FROM users
                    </select>
                </mapper>
                """);
        // 壊れた mapper XML の namespace の定義は見ない。
        writeXml("Broken", """
                <mapper namespace="com.example.Broken">
                    <sql id="maybeUsed">ID</sql>
                    <select id="find">SELECT <include refid="maybeUsed"/>
                """);
        writeXml("Other", """
                <mapper namespace="com.example.Other">
                    <sql id="unused">ID</sql>
                </mapper>
                """);

        assertEquals(List.of("unused-sql-fragment:2"), summary(unused()));
    }

    @Test
    void skipsResultMapsWhenAnAnnotationValueIsNotALiteral() throws IOException {
        writeJava("UserMapper", """
                package com.example;
                import org.apache.ibatis.annotations.ResultMap;
                public interface UserMapper {
                    String MAP = "constantMap";
                    @ResultMap(MAP)
                    User find();
                }
                """);
        writeXml("UserMapper", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="constantMap" type="map"/>
                </mapper>
                """);

        assertEquals(List.of(), unused());
    }

    private static List<String> summary(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> d.getRuleId().replace("mybatis-lint/", "") + ":" + d.getRange().getStart().getLine())
                .toList();
    }

    private List<Diagnostic> unused() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RULES.contains(d.getRuleId()))
                .toList();
    }

    private void writeJava(String name, String contents) throws IOException {
        write("src/main/java/com/example/" + name + ".java", contents);
    }

    private Path writeXml(String name, String contents) throws IOException {
        return write("src/main/resources/com/example/" + name + ".xml", contents);
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
