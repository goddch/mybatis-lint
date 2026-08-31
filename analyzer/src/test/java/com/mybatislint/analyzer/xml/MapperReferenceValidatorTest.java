package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperReferenceValidatorTest {

    @Test
    void reportsOnlyUnresolvedStaticIncludes(@TempDir Path tempDir) throws IOException {
        Path commonXml = tempDir.resolve("src/main/resources/com/example/CommonFragments.xml");
        Files.createDirectories(commonXml.getParent());
        Files.writeString(commonXml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.CommonFragments">
                    <sql id="BaseColumns">id, name</sql>
                </mapper>
                """, StandardCharsets.UTF_8);

        Path userXml = tempDir.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.writeString(userXml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <sql id="LocalWhere">WHERE deleted = 0</sql>
                    <select id="findAll">
                        SELECT
                        <include refid="com.example.CommonFragments.BaseColumns" />
                        FROM users
                        <include refid="LocalWhere" />
                        <include refid="${tenantPrefix}.DynamicColumns" />
                        <include refid="MissingLocal" />
                        <include refid="com.example.CommonFragments.MissingColumns" />
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        MapperSqlFragmentIndex sqlIndex = MapperSqlFragmentIndex.build(List.of(commonXml, userXml));
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(
                userXml, userXml.getFileName(), userXml.toUri().toString(), JavaMapperIndex.empty(), sqlIndex);

        // 正常系(local/FQN)とフェイルオープン系(${...})は除外され、
        // 静的に「存在しない」と言い切れる refid だけが残ることを確認する。
        List<Diagnostic> unresolved = diagnostics.stream()
                .filter(d -> RuleIds.UNRESOLVED_INCLUDE.equals(d.getRuleId()))
                .toList();
        assertEquals(2, unresolved.size(), () -> diagnostics.toString());
        assertTrue(unresolved.stream().anyMatch(d -> d.getMessage().contains("MissingLocal")),
                () -> diagnostics.toString());
        assertTrue(unresolved.stream().anyMatch(d -> d.getMessage().contains("com.example.CommonFragments.MissingColumns")),
                () -> diagnostics.toString());
        assertEquals(10, unresolved.get(0).getRange().getStart().getLine());
        assertTrue(unresolved.stream().allMatch(d -> d.getSeverity() == Severity.ERROR));
    }

    @Test
    void blankIncludeDoesNotShiftLaterIncludeRange(@TempDir Path tempDir) throws IOException {
        Path xmlFile = tempDir.resolve("src/main/resources/com/example/BlankIncludeMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.BlankIncludeMapper">
                    <select id="findAll">
                        <include />
                        <include refid="MissingColumns" />
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString());

        // refid が空の include は検証対象外だが、位置検索のカーソルは進める。
        // そうしないと、次の include の診断位置が空 include 側へずれてしまう。
        List<Diagnostic> unresolved = diagnostics.stream()
                .filter(d -> RuleIds.UNRESOLVED_INCLUDE.equals(d.getRuleId()))
                .toList();
        assertEquals(1, unresolved.size(), () -> diagnostics.toString());
        assertEquals(5, unresolved.get(0).getRange().getStart().getLine());
    }

    @Test
    void acceptsFullyQualifiedRefidInSameMapperWithoutWorkspaceIndex(@TempDir Path tempDir) throws IOException {
        Path xmlFile = tempDir.resolve("src/main/resources/com/example/SameMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.SameMapper">
                    <sql id="BaseColumns">id, name</sql>
                    <select id="findAll">
                        SELECT <include refid="com.example.SameMapper.BaseColumns" />
                        FROM users
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString());

        // 単一ファイル解析でも、現在の namespace で完全修飾された refid は
        // 同じ mapper 内の <sql id> へ戻して解決できるようにしている。
        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNRESOLVED_INCLUDE.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsResultMapAndResultTypeAsWarning(@TempDir Path tempDir) throws IOException {
        Path xmlFile = tempDir.resolve("src/main/resources/com/example/ResultMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.ResultMapper">
                    <resultMap id="UserResultMap" type="com.example.User" />
                    <select id="findNames" resultType="java.lang.String">
                        SELECT name FROM users
                    </select>
                    <insert id="insertUser">
                        <selectKey keyProperty="id" resultType="long">
                            SELECT user_seq.nextval FROM dual
                        </selectKey>
                        INSERT INTO users (id, name) VALUES (#{id}, #{name})
                    </insert>
                    <select id="findAll" resultMap="UserResultMap" resultType="com.example.User">
                        SELECT id, name FROM users
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString());

        // 直前に正常な <select> や <selectKey> があっても、診断位置が別の
        // statement/類似タグに引っ張られず、実際に両属性を書いた statement を
        // 指すことも確認する。
        List<Diagnostic> warnings = diagnostics.stream()
                .filter(d -> RuleIds.RESULT_MAP_AND_TYPE.equals(d.getRuleId()))
                .toList();
        assertEquals(1, warnings.size(), () -> diagnostics.toString());
        assertEquals(Severity.WARNING, warnings.get(0).getSeverity());
        assertEquals(13, warnings.get(0).getRange().getStart().getLine());
    }
}
