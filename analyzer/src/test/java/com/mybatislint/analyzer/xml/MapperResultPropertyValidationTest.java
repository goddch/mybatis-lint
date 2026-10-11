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

class MapperResultPropertyValidationTest {

    @Test
    void reportsUnknownDirectPropertiesWithAttributePositions(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <id column="id" property="id"/>
                        <result column="name" property="name"/>
                        <result column="label" property="displayName"/>
                        <result column="url" property="URL"/>
                        <id column="bad_id" property="missingId"/>
                        <result column="bad_name" property='missingName'/>
                    </resultMap>
                </mapper>
                """);

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));

        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
        assertEquals(7, diagnostics.get(0).getRange().getStart().getLine());
        assertEquals(39, diagnostics.get(0).getRange().getStart().getCharacter());
        assertEquals(8, diagnostics.get(1).getRange().getStart().getLine());
        assertEquals(45, diagnostics.get(1).getRange().getStart().getCharacter());
        assertTrue(diagnostics.get(0).getMessage().contains("missingId"));
        assertTrue(diagnostics.get(0).getMessage().contains("com.example.User"));
        assertEquals(xmlFile.getFileName(), diagnostics.get(0).getFile());
        assertEquals(xmlFile.toUri().toString(), diagnostics.get(0).getUri());
    }

    @Test
    void skipsDynamicNestedAndMissingProperties(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <id/>
                        <result property=" "/>
                        <result property="${property}"/>
                        <result property="address.street"/>
                        <result property="addresses[0]"/>
                        <association property="missingAssociation" javaType="com.example.Address">
                            <result property="missingNested"/>
                        </association>
                        <collection property="missingCollection">
                            <id property="missingNestedId"/>
                        </collection>
                        <constructor><arg name="missingArgument"/></constructor>
                    </resultMap>
                </mapper>
                """);

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
    }

    @Test
    void nestedMappingsDoNotShiftLaterDirectMappingPositions(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <association property="address">
                            <result property="missing"/>
                        </association>
                        <result property="missing"/>
                    </resultMap>
                    <resultMap id="OtherMap" type="com.example.User">
                        <result property="missing"/>
                    </resultMap>
                </mapper>
                """);

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));

        assertEquals(2, diagnostics.size());
        assertEquals(6, diagnostics.get(0).getRange().getStart().getLine());
        assertEquals(9, diagnostics.get(1).getRange().getStart().getLine());
    }

    @Test
    void skipsUnresolvedAliasesDynamicTypesAndInheritedResultMaps(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="Simple" type="User"><result property="missing"/></resultMap>
                    <resultMap id="Alias" type="userAlias"><result property="missing"/></resultMap>
                    <resultMap id="Map" type="map"><result property="anyKey"/></resultMap>
                    <resultMap id="QualifiedMap" type="java.util.Map"><result property="anyKey"/></resultMap>
                    <resultMap id="Unknown" type="com.example.Unknown"><result property="missing"/></resultMap>
                    <resultMap id="Dynamic" type="${dto}"><result property="missing"/></resultMap>
                    <resultMap id="Inherited" type="com.example.User" extends="BaseMap">
                        <result property="missing"/>
                    </resultMap>
                    <resultMap id="NoType"><result property="missing"/></resultMap>
                </mapper>
                """);

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
        assertTrue(propertyDiagnostics(new MapperXmlAnalyzer().analyze(
                xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString())).isEmpty());
    }

    @Test
    void cliPublishesWarningsWithoutChangingErrorExitStatus(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User"><result property="typo"/></resultMap>
                    <select id="findAll" resultMap="UserMap">SELECT name FROM users</select>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(tempDir);

        assertEquals(1, propertyDiagnostics(result.getDiagnostics()).size());
        assertFalse(result.hasErrors());
    }

    @Test
    void skipsInheritedAnnotatedAndRecordTypes(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Path types = tempDir.resolve("src/main/java/com/example/Types.java");
        Files.writeString(types, """
                package com.example;
                class Child extends User {}
                @Data class Generated {}
                record Summary(Long id) {}
                """, StandardCharsets.UTF_8);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="Child" type="com.example.Child"><id property="id"/></resultMap>
                    <resultMap id="Generated" type="com.example.Generated"><result property="name"/></resultMap>
                    <resultMap id="Summary" type="com.example.Summary"><id property="id"/></resultMap>
                </mapper>
                """);

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
    }

    @Test
    void skipsMalformedAndNonMapperXml(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <configuration>
                    <resultMap type="com.example.User"><result property="typo"/></resultMap>
                </configuration>
                """);
        assertTrue(analyze(tempDir, xmlFile).isEmpty());

        Files.writeString(xmlFile, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap type="com.example.User"><result property="typo"/></resultMap>
                """, StandardCharsets.UTF_8);
        List<Diagnostic> diagnostics = analyze(tempDir, xmlFile);
        assertTrue(propertyDiagnostics(diagnostics).isEmpty());
        assertTrue(diagnostics.stream().anyMatch(d -> RuleIds.MALFORMED_XML.equals(d.getRuleId())));
    }

    private static List<Diagnostic> analyze(Path tempDir, Path xmlFile) {
        return new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(),
                JavaMapperIndex.empty(), MapperSqlFragmentIndex.empty(), JavaTypeIndex.build(tempDir));
    }

    private static List<Diagnostic> propertyDiagnostics(List<Diagnostic> diagnostics) {
        return diagnostics.stream().filter(d -> RuleIds.UNKNOWN_RESULT_PROPERTY.equals(d.getRuleId())).toList();
    }

    private static void writeJavaType(Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/User.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;
                public class User {
                    private Long id;
                    private String name;
                    public void setDisplayName(String value) {}
                    public void setURL(String value) {}
                }
                """, StandardCharsets.UTF_8);
    }

    private static Path writeMapper(Path tempDir, String xml) throws IOException {
        Path xmlFile = tempDir.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        return xmlFile;
    }
}
