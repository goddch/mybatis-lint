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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperSelectAliasValidatorTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void writeDomainClasses() throws IOException {
        write("src/main/java/com/example/BaseDomain.java", """
                package com.example;
                import lombok.Data;
                @Data
                public abstract class BaseDomain { private String createdBy; }
                """);
        write("src/main/java/com/example/UserDomain.java", """
                package com.example;
                import lombok.Data;
                @Data
                public class UserDomain extends BaseDomain {
                    private Long userId;
                    private String userName;
                    private boolean isActive;
                    public String getDisplayName() { return userName; }
                }
                """);
    }

    @Test
    void reportsAliasesWithoutAMatchingPropertyAtTheAlias() throws IOException {
        Path file = writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId, u.USER_NAME AS userNmae, u.NICKNAME AS displayName
                      FROM users u
                </select>
                """);

        List<Diagnostic> warnings = aliasDiagnostics();
        assertEquals(2, warnings.size(), warnings::toString);
        String line = Files.readAllLines(file).get(3);
        Diagnostic first = warnings.get(0);
        assertEquals(Severity.WARNING, first.getSeverity());
        assertEquals(4, first.getRange().getStart().getLine());
        assertEquals(line.indexOf("userNmae") + 1, first.getRange().getStart().getCharacter());
        assertEquals(line.indexOf("userNmae") + 1 + "userNmae".length(), first.getRange().getEnd().getCharacter());
        assertTrue(first.getMessage().contains("'userNmae'"));
        assertTrue(first.getMessage().contains("com.example.UserDomain"));
        // getter しかない displayName には MyBatis が値を書き込めない。
        assertTrue(warnings.get(1).getMessage().contains("'displayName'"));
    }

    @Test
    void acceptsCaseUnderscoreLombokAndInheritedNames() throws IOException {
        writeMapper("""
                <select id="find" resultType="UserDomain">
                    SELECT /*+ INDEX(u USER_PK) */ DISTINCT
                           u.USER_ID AS USERID,
                           u.USER_NAME AS user_name, -- mapUnderscoreToCamelCase
                           NVL(u.ACTIVE, '0') AS "active",
                           u.CREATED_BY AS createdBy,
                           (SELECT MAX(x.a) AS bogus FROM x) AS isActive
                      FROM users u
                    <where><if test="userId != null">AND u.USER_ID = #{userId}</if></where>
                </select>
                <select id="cdata" resultType="UserDomain"><![CDATA[
                    SELECT CASE WHEN u.AGE < 20 THEN 'Y' ELSE 'N' END AS userName FROM users u
                ]]></select>
                """);

        assertTrue(aliasDiagnostics().isEmpty(), () -> aliasDiagnostics().toString());
    }

    @Test
    void skipsSelectListsThatCannotBeDetermined() throws IOException {
        writeMapper("""
                <sql id="cols">u.USER_ID AS userId</sql>
                <select id="star" resultType="UserDomain">SELECT u.*, 1 AS missing FROM users u</select>
                <select id="include" resultType="UserDomain">SELECT <include refid="cols"/>, 1 AS missing FROM users u</select>
                <select id="dynamic" resultType="UserDomain">SELECT ${cols}, 1 AS missing FROM users u</select>
                <select id="ifInList" resultType="UserDomain">SELECT 1 AS missing <if test="x">, 2 AS other</if> FROM t</select>
                <select id="with" resultType="UserDomain">WITH x AS (SELECT 1 AS a FROM dual) SELECT a AS missing FROM x</select>
                <select id="noFrom" resultType="UserDomain">SELECT 1 AS missing</select>
                <select id="resultMap" resultMap="Other" resultType="UserDomain">SELECT 1 AS missing FROM t</select>
                <select id="map" resultType="map">SELECT 1 AS missing FROM t</select>
                <select id="unknownType" resultType="com.example.Unknown">SELECT 1 AS missing FROM t</select>
                <select id="dynamicType" resultType="${type}">SELECT 1 AS missing FROM t</select>
                <select id="nested" resultType="UserDomain">SELECT 1 AS "address.street" FROM t</select>
                <select id="noAlias" resultType="UserDomain">SELECT u.MISSING_COLUMN, u.OTHER other FROM users u</select>
                """);

        assertTrue(aliasDiagnostics().isEmpty(), () -> aliasDiagnostics().toString());
    }

    @Test
    void skipsTypesThatCannotBeResolvedUniquely() throws IOException {
        // 同じ simple name のクラスが 2 つあると、resultType="Order" がどちらか決められない。
        write("src/main/java/com/example/a/Order.java", "package com.example.a; public class Order { }");
        write("src/main/java/com/example/b/Order.java", "package com.example.b; public class Order { }");
        // 設定ファイルの alias は指す先を追っていない。
        write("src/main/resources/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="UserDomain" type="com.example.b.Order"/>
                </typeAliases></configuration>
                """);
        writeMapper("""
                <select id="order" resultType="Order">SELECT 1 AS missing FROM t</select>
                <select id="configAlias" resultType="UserDomain">SELECT 1 AS missing FROM t</select>
                """);

        assertTrue(aliasDiagnostics().isEmpty(), () -> aliasDiagnostics().toString());
    }

    @Test
    void resolvesAliasAnnotations() throws IOException {
        write("src/main/java/com/example/Member.java", """
                package com.example;
                @org.apache.ibatis.type.Alias("member")
                public class Member { private String name; }
                """);
        writeMapper("""
                <select id="find" resultType="member">SELECT 1 AS name, 2 AS nmae FROM t</select>
                """);

        List<Diagnostic> warnings = aliasDiagnostics();
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).getMessage().contains("'nmae'"));
        assertFalse(new AnalyzerCli().run(workspace).hasErrors());
    }

    @Test
    void extractsOnlyExplicitAliases() {
        assertEquals(List.of("a", "c"), MapperSelectAliasValidator.extractSelectAliases(
                "select x as a, y b, 'it''s, from' AS c, count(*) from t"));
        assertNull(MapperSelectAliasValidator.extractSelectAliases("update t set a = 1"));
    }

    private List<Diagnostic> aliasDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.UNKNOWN_SELECT_ALIAS.equals(d.getRuleId()))
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
