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
    void skipsNonSimplePropertiesButChecksNestedMappingProperties(@TempDir Path tempDir) throws IOException {
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

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.get(0).getMessage().contains("missingAssociation"));
        assertTrue(diagnostics.get(1).getMessage().contains("missingCollection"));
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

        assertEquals(3, diagnostics.size());
        assertEquals(3, diagnostics.get(0).getRange().getStart().getLine());
        assertEquals(6, diagnostics.get(1).getRange().getStart().getLine());
        assertEquals(9, diagnostics.get(2).getRange().getStart().getLine());
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
    void skipsResultMapsWithDiscriminatorsSelectingSubtypeProperties(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Person.java"), """
                package com.example;
                class Person extends User { private String personName; }
                """, StandardCharsets.UTF_8);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result column="name" property="personName"/>
                        <discriminator column="kind" javaType="string">
                            <case value="person" resultType="com.example.Person"/>
                        </discriminator>
                    </resultMap>
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

    @Test
    void validatesExplicitNestedTypesAndPreservesDocumentOrder(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Address.java"), """
                package com.example;
                public class Address { private String street; }
                """, StandardCharsets.UTF_8);
        String xml = """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <association property="address" javaType=" com.example.Address ">
                            <result property="street"/>
                            <id property='id'/>
                            <collection property="users" javaType="java.util.ArrayList" ofType="com.example.User">
                                <id property="id"/>
                                <result property="displayName"/>
                                <result property="URL"/>
                                <result property="street"/>
                                <association property="address" javaType="com.example.Address">
                                    <result property="street"/>
                                    <result property="name"/>
                                </association>
                            </collection>
                        </association>
                        <result property="street"/>
                    </resultMap>
                </mapper>
                """;
        Path xmlFile = writeMapper(tempDir, xml);

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));

        assertEquals(7, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
        int[] lines = {3, 5, 6, 10, 11, 13, 17};
        String[] types = {"User", "Address", "Address", "User", "User", "Address", "User"};
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic diagnostic = diagnostics.get(i);
            assertEquals(lines[i], diagnostic.getRange().getStart().getLine());
            String line = xml.lines().toList().get(lines[i] - 1);
            assertEquals(line.indexOf("property=") + "property=".length() + 2,
                    diagnostic.getRange().getStart().getCharacter());
            assertTrue(diagnostic.getMessage().contains("com.example." + types[i]));
        }
    }

    @Test
    void skipsNestedTypesThatCannotBeResolvedConfidently(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Types.java"), """
                package com.example;
                class Child extends User {}
                @Data class Generated {}
                record Summary(Long id) {}
                class Generic<T> {}
                interface Contract {}
                abstract class AbstractUser {}
                class Outer { class Inner {} }
                class Duplicate {}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("Duplicate.java"),
                "package com.example; class Duplicate {}", StandardCharsets.UTF_8);
        StringBuilder mappings = new StringBuilder();
        for (String type : List.of("", "User", "userAlias", "map", "java.util.Map",
                "com.example.Unknown", "${dto}", "com.example.Child", "com.example.Generated",
                "com.example.Summary", "com.example.Generic", "com.example.Contract",
                "com.example.AbstractUser", "com.example.Outer.Inner", "com.example.Duplicate")) {
            mappings.append("<association javaType=\"").append(type)
                    .append("\"><result property=\"missing\"/></association>\n");
            mappings.append("<collection ofType=\"").append(type)
                    .append("\"><id property=\"missing\"/></collection>\n");
        }
        mappings.append("""
                <collection javaType="com.example.User"><result property="missing"/></collection>
                <association ofType="com.example.User"><result property="missing"/></association>
                """);
        Path xmlFile = writeMapper(tempDir, "<mapper namespace=\"example.Mapper\">"
                + "<resultMap id=\"Map\" type=\"com.example.User\">" + mappings
                + "</resultMap></mapper>");

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
        assertTrue(propertyDiagnostics(new MapperXmlAnalyzer().analyze(
                xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString())).isEmpty());
    }

    @Test
    void skipsReferencedSelectedInheritedDiscriminatedAndOutsideMappings(@TempDir Path tempDir)
            throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association javaType="com.example.User" resultMap="Map">
                            <result property="missing"/>
                            <collection ofType="com.example.User"><id property="missing"/></collection>
                        </association>
                        <collection ofType="com.example.User" select="find">
                            <id property="missing"/>
                            <association javaType="com.example.User"><result property="missing"/></association>
                        </collection>
                        <association javaType="com.example.User" extends="Base">
                            <result property="missing"/>
                        </association>
                        <constructor><arg javaType="com.example.User">
                            <association javaType="com.example.User"><result property="missing"/></association>
                        </arg></constructor>
                        <resultMap type="com.example.User">
                            <association javaType="com.example.User"><result property="missing"/></association>
                        </resultMap>
                    </resultMap>
                    <resultMap id="Inherited" type="com.example.User" extends="Map">
                        <collection ofType="com.example.User"><id property="missing"/></collection>
                    </resultMap>
                    <resultMap id="Discriminated" type="com.example.User">
                        <association javaType="com.example.User">
                            <result property="missing"/>
                            <discriminator column="kind" javaType="string">
                                <case value="other" resultType="com.example.Other">
                                    <collection ofType="com.example.User"><id property="missing"/></collection>
                                </case>
                            </discriminator>
                        </association>
                    </resultMap>
                    <sql id="fragment"><association javaType="com.example.User">
                        <result property="missing"/>
                    </association></sql>
                </mapper>
                """);

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
    }

    @Test
    void skipsNonSimpleNestedPropertiesAndDoesNotRequireParentTypeInference(@TempDir Path tempDir)
            throws IOException {
        writeJavaType(tempDir);
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="map">
                        <collection>
                            <id property="missing"/>
                            <association javaType="com.example.User">
                                <result/>
                                <result property=" "/>
                                <result property="${property}"/>
                                <result property="address.street"/>
                                <result property="addresses[0]"/>
                                <constructor><arg name="missing"/></constructor>
                                <result property="typo"/>
                            </association>
                        </collection>
                    </resultMap>
                </mapper>
                """);

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));
        assertEquals(1, diagnostics.size());
        assertEquals(12, diagnostics.get(0).getRange().getStart().getLine());
    }

    @Test
    void cliPublishesNestedPropertyWarningsWithoutErrors(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="map">
                        <association javaType="com.example.User"><result property="typo"/></association>
                        <collection ofType="com.example.User"><id property="typo"/></collection>
                    </resultMap>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(tempDir);
        assertEquals(2, propertyDiagnostics(result.getDiagnostics()).size());
        assertFalse(result.hasErrors());
    }

    @Test
    void validatesNestedMappingPropertiesAgainstImmediateParentTypes(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Address.java"), """
                package com.example;
                class Address { private String street; public void setResidents(Object value) {} }
                """, StandardCharsets.UTF_8);
        String xml = """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association property="name" javaType="com.example.Address">
                            <collection property="residents" ofType="com.example.User">
                                <association property="displayName"/>
                                <collection property="URL"/>
                                <association property='street'/>
                            </collection>
                            <association property="street"/>
                            <collection property="name" javaType="com.example.User"/>
                        </association>
                        <collection property="id"/>
                        <association property="Name"/>
                    </resultMap>
                </mapper>
                """;
        Path xmlFile = writeMapper(tempDir, xml);

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));

        assertEquals(3, diagnostics.size());
        int[] lines = {7, 10, 13};
        String[] types = {"com.example.User", "com.example.Address", "com.example.User"};
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic diagnostic = diagnostics.get(i);
            assertEquals(Severity.WARNING, diagnostic.getSeverity());
            assertEquals(lines[i], diagnostic.getRange().getStart().getLine());
            String line = xml.lines().toList().get(lines[i] - 1);
            assertEquals(line.indexOf("property=") + "property=".length() + 2,
                    diagnostic.getRange().getStart().getCharacter());
            assertTrue(diagnostic.getMessage().contains(types[i]));
        }
    }

    @Test
    void skipsUncertainNestedMappingPropertiesAndContexts(@TempDir Path tempDir) throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Types.java"), """
                package com.example;
                class Child extends User {}
                @Data class Generated {}
                record Summary(Long id) {}
                class Generic<T> {}
                interface Contract {}
                abstract class AbstractUser {}
                class Outer { class Inner {} }
                class Duplicate {}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("Duplicate.java"),
                "package com.example; class Duplicate {}", StandardCharsets.UTF_8);
        StringBuilder resultMaps = new StringBuilder();
        for (String type : List.of("", "User", "userAlias", "map", "java.util.Map",
                "com.example.Unknown", "${dto}", "com.example.Child", "com.example.Generated",
                "com.example.Summary", "com.example.Generic", "com.example.Contract",
                "com.example.AbstractUser", "com.example.Outer.Inner", "com.example.Duplicate")) {
            resultMaps.append("<resultMap type=\"").append(type).append("\">")
                    .append("<association property=\"missing\"/><collection property=\"missing\"/>")
                    .append("</resultMap>");
        }
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association/>
                        <collection property=" "/>
                        <association property="${property}"/>
                        <collection property="address.street"/>
                        <association property="addresses[0]"/>
                        <collection property="name" resultMap="Map">
                            <association property="missing" javaType="com.example.User"/>
                        </collection>
                        <association property="name" select="find"/>
                        <collection property="missing" extends="Map"/>
                        <association property="name">
                            <collection property="missing"/>
                        </association>
                        <collection property="name" javaType="com.example.User">
                            <association property="missing"/>
                        </collection>
                        <constructor><arg><association property="missing"/></arg></constructor>
                        <resultMap type="com.example.User"><collection property="missing"/></resultMap>
                    </resultMap>
                    <resultMap id="Inherited" type="com.example.User" extends="Map">
                        <association property="missing"/>
                    </resultMap>
                    <resultMap id="Discriminated" type="com.example.User">
                        <collection property="missing"/>
                        <discriminator column="kind" javaType="string"/>
                    </resultMap>
                    <sql id="fragment"><association property="missing" javaType="com.example.User"/></sql>
                """ + resultMaps + "</mapper>");

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
        assertTrue(propertyDiagnostics(new MapperXmlAnalyzer().analyze(
                xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString())).isEmpty());
    }

    @Test
    void validatesReferencedAndSelectedMappingPropertiesWithoutExpandingChildren(@TempDir Path tempDir)
            throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Address.java"), """
                package com.example;
                class Address { private String street; }
                """, StandardCharsets.UTF_8);
        String xml = """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association property="name" resultMap="Other"/>
                        <collection property="displayName" select="find"/>
                        <association property="address" resultMap="Other" javaType="com.example.Address">
                            <result property="missing"/>
                            <collection property="missing" ofType="com.example.User">
                                <id property="missing"/>
                            </collection>
                        </association>
                        <collection property='users' select="find" ofType="com.example.User">
                            <id property="missing"/>
                            <association property="missing" javaType="com.example.Address"/>
                        </collection>
                        <association property="name" javaType="com.example.Address">
                            <collection property="users" resultMap="Other"/>
                            <association property="street" select="find"/>
                            <association property="name" select="find"/>
                        </association>
                    </resultMap>
                    <resultMap id="Other" type="map"/>
                </mapper>
                """;
        Path xmlFile = writeMapper(tempDir, xml);

        List<Diagnostic> diagnostics = propertyDiagnostics(analyze(tempDir, xmlFile));

        assertEquals(4, diagnostics.size());
        int[] lines = {5, 11, 16, 18};
        String[] types = {"com.example.User", "com.example.User", "com.example.Address", "com.example.Address"};
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic diagnostic = diagnostics.get(i);
            assertEquals(Severity.WARNING, diagnostic.getSeverity());
            assertEquals(lines[i], diagnostic.getRange().getStart().getLine());
            String line = xml.lines().toList().get(lines[i] - 1);
            int start = line.indexOf("property=") + "property=".length() + 2;
            assertEquals(start, diagnostic.getRange().getStart().getCharacter());
            assertTrue(diagnostic.getMessage().contains(types[i]));
            assertEquals(xmlFile.getFileName(), diagnostic.getFile());
            assertEquals(xmlFile.toUri().toString(), diagnostic.getUri());
        }
    }

    @Test
    void referencedAndSelectedMappingPropertiesRemainFailOpenForUncertainContexts(@TempDir Path tempDir)
            throws IOException {
        writeJavaType(tempDir);
        Files.writeString(tempDir.resolve("src/main/java/com/example/Types.java"), """
                package com.example;
                @Data class Generated {}
                class Duplicate {}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("Duplicate.java"),
                "package com.example; class Duplicate {}", StandardCharsets.UTF_8);
        StringBuilder mappings = new StringBuilder();
        for (String type : List.of("", "User", "map", "${dto}", "com.example.Unknown",
                "com.example.Generated", "com.example.Duplicate")) {
            mappings.append("<resultMap type=\"").append(type).append("\">")
                    .append("<association property=\"missing\" resultMap=\"Other\"/>")
                    .append("<collection property=\"missing\" select=\"find\"/>")
                    .append("</resultMap>");
        }
        Path xmlFile = writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association property="${property}" resultMap="Other"/>
                        <collection property="address.street" select="find"/>
                        <association property="addresses[0]" resultMap="Other"/>
                        <collection property=" " select="find"/>
                        <association resultMap="Other"/>
                    </resultMap>
                    <resultMap id="Inherited" type="com.example.User" extends="Map">
                        <association property="missing" resultMap="Other"/>
                        <collection property="missing" select="find"/>
                    </resultMap>
                    <resultMap id="Discriminated" type="com.example.User">
                        <association property="missing" resultMap="Other"/>
                        <collection property="missing" select="find"/>
                        <discriminator column="kind" javaType="string"/>
                    </resultMap>
                    <sql id="fragment"><association property="missing" resultMap="Other"/></sql>
                """ + mappings + "</mapper>");

        assertTrue(propertyDiagnostics(analyze(tempDir, xmlFile)).isEmpty());
        assertTrue(propertyDiagnostics(new MapperXmlAnalyzer().analyze(
                xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString())).isEmpty());
    }

    @Test
    void cliPublishesReferencedAndSelectedPropertyWarningsAlongsideReferenceWarnings(@TempDir Path tempDir)
            throws IOException {
        writeJavaType(tempDir);
        writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association property="typo" resultMap="Missing"/>
                        <collection property="typo" select="find"/>
                    </resultMap>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(tempDir);

        assertEquals(2, propertyDiagnostics(result.getDiagnostics()).size());
        assertEquals(1, result.getDiagnostics().stream()
                .filter(d -> RuleIds.UNRESOLVED_NESTED_RESULT_MAP.equals(d.getRuleId())).count());
        assertFalse(result.hasErrors());
    }

    @Test
    void cliPublishesAssociationAndCollectionPropertyWarningsWithoutErrors(@TempDir Path tempDir)
            throws IOException {
        writeJavaType(tempDir);
        writeMapper(tempDir, """
                <mapper namespace="example.Mapper">
                    <resultMap id="Map" type="com.example.User">
                        <association property="typo"/>
                        <collection property="typo"/>
                    </resultMap>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(tempDir);

        assertEquals(2, propertyDiagnostics(result.getDiagnostics()).size());
        assertFalse(result.hasErrors());
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
