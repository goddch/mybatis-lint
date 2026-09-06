package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
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

/**
 * {@code mybatis-lint/unresolved-type-alias} の正常系・異常系・フェイルオープン系を
 * まとめて確認するテスト。{@link JavaTypeIndex} をワークスペース内の Java ソースから
 * 実際にビルドし、mapper XML の {@code type}/{@code parameterType}/{@code resultType}
 * を検証する。
 */
class MapperTypeAliasValidationTest {

    @Test
    void reportsUnresolvedUppercaseTypeAliasCandidates(@TempDir Path tempDir) throws IOException {
        writeJavaClass(tempDir, "User");

        Path xmlFile = writeMapperXml(tempDir, "TypeAliasMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.TypeAliasMapper">
                    <!-- User は workspace 内に User.java があるため解決済み -->
                    <resultMap id="UserResultMap" type="User" />
                    <select id="findByCriteria" parameterType="SearchCriteria" resultMap="UserResultMap">
                        SELECT id, name FROM users
                    </select>
                    <!-- UserAlias という simple name のクラスは workspace に存在しない -->
                    <select id="findAliased" resultType="UserAlias">
                        SELECT id, name FROM users
                    </select>
                </mapper>
                """);

        JavaTypeIndex typeIndex = JavaTypeIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(),
                xmlFile.toUri().toString(), JavaMapperIndex.empty(), MapperSqlFragmentIndex.empty(), typeIndex);

        List<Diagnostic> unresolved = diagnostics.stream()
                .filter(d -> RuleIds.UNRESOLVED_TYPE_ALIAS.equals(d.getRuleId()))
                .toList();
        // "User" は解決済みなので報告されない。"SearchCriteria" と "UserAlias" は
        // workspace 内に対応するクラスが無い、大文字始まりの simple name なので
        // 未解決候補として 2 件報告される。
        assertEquals(2, unresolved.size(), () -> diagnostics.toString());
        assertTrue(unresolved.stream().allMatch(d -> d.getSeverity() == Severity.WARNING),
                () -> diagnostics.toString());
        assertTrue(unresolved.stream().anyMatch(d -> d.getMessage().contains("SearchCriteria")),
                () -> diagnostics.toString());
        assertTrue(unresolved.stream().anyMatch(d -> d.getMessage().contains("UserAlias")),
                () -> diagnostics.toString());
    }

    @Test
    void doesNotReportFullyQualifiedNamesOrBuiltinOrLowercaseOrDynamicValues(@TempDir Path tempDir) throws IOException {
        Path xmlFile = writeMapperXml(tempDir, "SafeTypeAliasMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.SafeTypeAliasMapper">
                    <!-- 完全修飾クラス名はドットを含むため typeAlias 解決の対象外 -->
                    <resultMap id="UserResultMap" type="com.example.User" />
                    <select id="findAll" resultType="com.example.User">
                        SELECT id, name FROM users
                    </select>
                    <!-- MyBatis 組み込み alias(map/string/int など)はフェイルオープン -->
                    <select id="findAsMap" resultType="map" parameterType="string">
                        SELECT id, name FROM users
                    </select>
                    <select id="countAll" resultType="int">
                        SELECT count(*) FROM users
                    </select>
                    <!-- 先頭が小文字の値は個別登録された alias の可能性を否定できないためフェイルオープン -->
                    <select id="findLowercaseAlias" resultType="userAlias">
                        SELECT id, name FROM users
                    </select>
                    <!-- ${...} を含む動的な値は実行時に決まるためフェイルオープン -->
                    <select id="findDynamic" resultType="${resultTypeAlias}">
                        SELECT id, name FROM users
                    </select>
                </mapper>
                """);

        JavaTypeIndex typeIndex = JavaTypeIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(),
                xmlFile.toUri().toString(), JavaMapperIndex.empty(), MapperSqlFragmentIndex.empty(), typeIndex);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNRESOLVED_TYPE_ALIAS.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void resolvesTypeAliasAgainstWorkspaceClassRegardlessOfCase(@TempDir Path tempDir) throws IOException {
        writeJavaClass(tempDir, "OrderSummary");

        Path xmlFile = writeMapperXml(tempDir, "CaseInsensitiveMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.CaseInsensitiveMapper">
                    <!-- MyBatis の alias 解決は大文字小文字を区別しないため、
                         "Ordersummary" のような表記揺れも解決済みとして扱う -->
                    <select id="findOrders" resultType="Ordersummary">
                        SELECT id FROM orders
                    </select>
                </mapper>
                """);

        JavaTypeIndex typeIndex = JavaTypeIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(),
                xmlFile.toUri().toString(), JavaMapperIndex.empty(), MapperSqlFragmentIndex.empty(), typeIndex);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNRESOLVED_TYPE_ALIAS.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    private static Path writeMapperXml(Path tempDir, String fileName, String content) throws IOException {
        Path xmlFile = tempDir.resolve("src/main/resources/com/example/" + fileName);
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, content, StandardCharsets.UTF_8);
        return xmlFile;
    }

    private static void writeJavaClass(Path tempDir, String simpleName) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/" + simpleName + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                public class %s {
                }
                """.formatted(simpleName), StandardCharsets.UTF_8);
    }
}
