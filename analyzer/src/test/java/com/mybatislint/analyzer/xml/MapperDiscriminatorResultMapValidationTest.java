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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperDiscriminatorResultMapValidationTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesLocalForwardAndNamespaceQualifiedDeclarationsWithoutWorkspaceIndex() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="local" resultMap=" Base "/>
                            <case value="qualified" resultMap=" example.Mapper.Base "/>
                        </discriminator>
                    </resultMap>
                    <resultMap id="example.Mapper.Base" type="map"/>
                </mapper>
                """);
        assertTrue(new MapperXmlAnalyzer().analyze(file, file.getFileName(), file.toUri().toString()).isEmpty());
    }

    @Test
    void resolvesCrossFileReferencesIncludingDiscriminatorsInInlineNestedMappings() throws IOException {
        write("Shared.xml", """
                <mapper namespace="example.Mapper"><resultMap id="Shared" type="map"/></mapper>
                """);
        write("Other.xml", """
                <mapper namespace="other.Mapper"><resultMap id="other.Mapper.Base" type="map"/></mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="shared" resultMap="Shared"/>
                        </discriminator>
                        <association property="child" javaType="map">
                            <collection property="items" ofType="map">
                                <discriminator column="kind" javaType="string">
                                    <case value="other" resultMap="other.Mapper.Base"/>
                                </discriminator>
                            </collection>
                        </association>
                    </resultMap>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void reportsEachMissingReferenceAtItsAttributeValueWithoutErrors() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="local" resultMap='Missing'/>
                            <case value="remote" resultMap="other.Mapper.Missing"/>
                            <case value="inline" resultType="map"/>
                            <case value="repeated" resultMap="Missing"/>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        List<Diagnostic> warnings = discriminatorDiagnostics(result.getDiagnostics());
        assertEquals(3, warnings.size());
        assertEquals(List.of(4, 5, 7), warnings.stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        List<String> lines = Files.readAllLines(file);
        for (Diagnostic warning : warnings) {
            String line = lines.get(warning.getRange().getStart().getLine() - 1);
            assertEquals(line.indexOf(warning.getRange().getStart().getLine() == 5
                            ? "other.Mapper.Missing" : "Missing") + 1,
                    warning.getRange().getStart().getCharacter());
            assertEquals("Mapper.xml", warning.getFile().toString());
            assertEquals(file.toUri().toString(), warning.getUri());
            assertEquals(Severity.WARNING, warning.getSeverity());
            assertTrue(warning.getMessage().contains("Missing"));
        }
        assertEquals(warnings.size(), result.getDiagnostics().size());
        assertFalse(result.hasErrors());
    }

    @Test
    void skipsBlankDynamicCommaSeparatedAndInlineCases() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="absent"/>
                            <case value="blank" resultMap=" "/>
                            <case value="dynamic" resultMap="${mapping}"/>
                            <case value="multiple" resultMap="One, Two"/>
                            <case value="inline" resultType="map">
                                <result property="name" column="name"/>
                            </case>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void ignoresCasesOutsideDiscriminatorsOrTopLevelResultMapsAndFindsCorrectPosition() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <sql id="fragment">
                        <discriminator><case value="sql" resultMap="Missing"/></discriminator>
                    </sql>
                    <resultMap id="Parent" type="map">
                        <case value="notDiscriminator" resultMap="Missing"/>
                        <discriminator column="kind" javaType="string">
                            <!-- <case value="comment" resultMap="Missing"/> -->
                            <case value="real" resultMap="Missing"/>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        List<Diagnostic> warnings = discriminatorDiagnostics(new AnalyzerCli().run(workspace).getDiagnostics());
        assertEquals(1, warnings.size());
        assertEquals(9, warnings.get(0).getRange().getStart().getLine());
        assertEquals(Files.readAllLines(file).get(8).indexOf("Missing") + 1,
                warnings.get(0).getRange().getStart().getCharacter());
    }

    @Test
    void doesNotResolveAliasesSqlFragmentsNestedDeclarationsOrOtherNamespaceShortIds() throws IOException {
        write("Other.xml", """
                <mapper namespace="other.Mapper"><resultMap id="Base" type="map"/></mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <sql id="Columns">id</sql>
                    <resultMap id="Parent" type="map">
                        <collection property="items"><resultMap id="Nested" type="map"/></collection>
                        <discriminator column="kind" javaType="string">
                            <case value="alias" resultMap="map"/>
                            <case value="sql" resultMap="Columns"/>
                            <case value="nested" resultMap="Nested"/>
                            <case value="short" resultMap="Base"/>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        assertEquals(4, discriminatorDiagnostics(new AnalyzerCli().run(workspace).getDiagnostics()).size());
    }

    @Test
    void duplicateDeclarationsStillSatisfyReferenceExistence() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="base" resultMap="Base"/>
                        </discriminator>
                    </resultMap>
                    <resultMap id="Base" type="map"/>
                    <resultMap id="Base" type="string"/>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(discriminatorDiagnostics(result.getDiagnostics()).isEmpty());
        assertEquals(2, result.getDiagnostics().stream()
                .filter(d -> RuleIds.DUPLICATE_RESULT_MAP_ID.equals(d.getRuleId())).count());
    }

    @Test
    void skipsMissingBlankDynamicNamespacesAndNonMapperXml() throws IOException {
        String content = """
                <resultMap id="Parent" type="map">
                    <discriminator column="kind" javaType="string">
                        <case value="base" resultMap="Missing"/>
                    </discriminator>
                </resultMap>
                """;
        write("Missing.xml", "<mapper>" + content + "</mapper>");
        write("Blank.xml", "<mapper namespace=\" \">" + content + "</mapper>");
        write("Dynamic.xml", "<mapper namespace=\"${ns}\">" + content + "</mapper>");
        write("Config.xml", "<configuration>" + content + "</configuration>");
        assertTrue(discriminatorDiagnostics(new AnalyzerCli().run(workspace).getDiagnostics()).isEmpty());
    }

    @Test
    void malformedXmlDoesNotPreventResolutionInOtherFiles() throws IOException {
        write("Broken.xml", """
                <mapper namespace="example.Mapper"><resultMap id="Broken"><discriminator>
                    <case value="broken" resultMap="Missing"/>
                """);
        write("Base.xml", """
                <mapper namespace="example.Mapper"><resultMap id="Base" type="map"/></mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="base" resultMap="Base"/>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(result.hasErrors());
        assertTrue(discriminatorDiagnostics(result.getDiagnostics()).isEmpty());
        assertEquals(1, result.getDiagnostics().stream()
                .filter(d -> RuleIds.MALFORMED_XML.equals(d.getRuleId())).count());
    }

    private Path write(String name, String xml) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> discriminatorDiagnostics(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(d -> RuleIds.UNRESOLVED_DISCRIMINATOR_RESULT_MAP.equals(d.getRuleId()))
                .toList();
    }
}
