package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mybatis-lint/unresolved-type-alias} の正常系・異常系・フェイルオープン系を
 * まとめて確認するテスト。{@link JavaTypeIndex} をワークスペース内の Java ソースから
 * 実際にビルドし、mapper XML の明示的な型指定を検証する。
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

    @Test
    void reportsNestedMappingTypeAliasesAtEachAttributeValue(@TempDir Path tempDir) throws IOException {
        Path xmlFile = writeMapperXml(tempDir, "NestedMapper.xml", """
                <mapper namespace="com.example.NestedMapper">
                    <sql id="fragment"><association javaType="Ignored"/></sql>
                    <!-- <association javaType="MissingChild"/> -->
                    <resultMap id="Parent" type="map">
                        <association property="child" javaType='MissingChild'>
                            <collection property="items" javaType="MissingList" ofType=" MissingItem "/>
                        </association>
                        <collection property="other" ofType="MissingChild" resultMap="Parent"/>
                    </resultMap>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(tempDir);
        List<Diagnostic> warnings = result.getDiagnostics().stream()
                .filter(d -> RuleIds.UNRESOLVED_TYPE_ALIAS.equals(d.getRuleId())).toList();
        assertEquals(4, warnings.size(), () -> result.getDiagnostics().toString());
        List<String> lines = Files.readAllLines(xmlFile);
        List<String> values = List.of("MissingChild", "MissingList", " MissingItem ", "MissingChild");
        List<String> attributes = List.of("javaType", "javaType", "ofType", "ofType");
        List<Integer> lineNumbers = List.of(5, 6, 6, 8);
        for (int i = 0; i < warnings.size(); i++) {
            Diagnostic warning = warnings.get(i);
            String value = values.get(i);
            int line = lineNumbers.get(i);
            int character = lines.get(line - 1).indexOf(value) + 1;
            assertEquals(line, warning.getRange().getStart().getLine());
            assertEquals(character, warning.getRange().getStart().getCharacter());
            // 属性値全体に波線が引けるよう、終了位置は値の末尾(の次の桁)。
            assertEquals(line, warning.getRange().getEnd().getLine());
            assertEquals(character + value.length(), warning.getRange().getEnd().getCharacter());
            assertEquals(Severity.WARNING, warning.getSeverity());
            assertEquals(xmlFile.toUri().toString(), warning.getUri());
            assertTrue(warning.getMessage().contains("'" + value + "'"));
            assertTrue(warning.getMessage().contains("'" + attributes.get(i) + "'"));
        }
        assertFalse(result.hasErrors());
    }

    @Test
    void resolvesNestedMappingAliasesUsingWorkspaceTypesAndExistingFailOpenPolicy(@TempDir Path tempDir)
            throws IOException {
        writeJavaClass(tempDir, "Child");
        writeJavaClass(tempDir, "ChildList");
        writeMapperXml(tempDir, "ResolvedNestedMapper.xml", """
                <mapper namespace="com.example.ResolvedNestedMapper">
                    <resultMap id="Parent" type="map">
                        <association property="child" javaType=" Child "/>
                        <collection property="children" javaType="Childlist" ofType="CHILD"/>
                        <association property="fqcn" javaType="external.Missing"/>
                        <collection property="builtin" javaType="ARRAYLIST" ofType="STRING"/>
                        <association property="custom" javaType="registeredAlias"/>
                        <collection property="dynamic" javaType="${listType}" ofType="${itemType}"/>
                        <collection property="empty" javaType=" " ofType=""/>
                        <association property="inferred"/>
                        <collection property="nested">
                            <association property="child" javaType="Child"/>
                        </collection>
                    </resultMap>
                </mapper>
                """);

        assertTrue(new AnalyzerCli().run(tempDir).getDiagnostics().isEmpty());
    }

    @Test
    void skipsTypeAttributesOutsideSupportedNestedMappings(@TempDir Path tempDir) throws IOException {
        writeMapperXml(tempDir, "OutOfScopeMapper.xml", """
                <mapper namespace="com.example.OutOfScopeMapper">
                    <association javaType="Outside"/>
                    <collection javaType="OutsideList" ofType="OutsideItem"/>
                    <sql id="fragment"><collection javaType="FragmentList" ofType="FragmentItem"/></sql>
                    <select id="find" javaType="StatementType" ofType="StatementItem">
                        <association javaType="StatementChild"/>
                    </select>
                    <resultMap id="Parent" type="map">
                        <id property="id" javaType="IdType"/>
                        <result property="value" javaType="ResultType"/>
                        <association property="child" ofType="UnsupportedAttribute"/>
                    </resultMap>
                </mapper>
                """);

        assertTrue(new AnalyzerCli().run(tempDir).getDiagnostics().isEmpty());
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
