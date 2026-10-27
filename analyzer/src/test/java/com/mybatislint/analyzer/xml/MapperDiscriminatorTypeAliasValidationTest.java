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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperDiscriminatorTypeAliasValidationTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void writeUnrelatedJavaType() throws IOException {
        // Java ソースが 1 つもないと typeAlias の推測自体をしないので、無関係な型を置いておく。
        write("Unrelated.java", "package com.example; public class Unrelated {}");
    }

    @Test
    void reportsDiscriminatorAndInlineCaseAliasesAtAttributeValues() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <!-- <discriminator javaType="MissingKind"><case resultType="MissingCase"/></discriminator> -->
                    <sql id="fragment"><![CDATA[<case resultType="MissingCase"/>]]></sql>
                    <?example <discriminator javaType="MissingKind"/> ?>
                    <resultMap id="Base" type="map">
                        <discriminator column="kind" javaType='MissingKind'>
                            <case value="one" resultType="MissingCase"/>
                            <case value="two"
                                resultType=" MissingCase "/>
                        </discriminator>
                        <association property="child" javaType="map">
                            <collection property="items" ofType="map">
                                <discriminator column="kind" javaType="MissingKind">
                                    <case value="nested" resultType="MissingNested"/>
                                </discriminator>
                            </collection>
                        </association>
                    </resultMap>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        List<Diagnostic> diagnostics = aliases(result.getDiagnostics());
        assertEquals(5, diagnostics.size());
        List<Integer> lineNumbers = List.of(6, 7, 9, 13, 14);
        List<String> values = List.of("MissingKind", "MissingCase", " MissingCase ", "MissingKind", "MissingNested");
        List<String> attributes = List.of("javaType", "resultType", "resultType", "javaType", "resultType");
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic diagnostic = diagnostics.get(i);
            int line = lineNumbers.get(i);
            int character = lines.get(line - 1).indexOf(values.get(i)) + 1;
            assertEquals(line, diagnostic.getRange().getStart().getLine());
            assertEquals(character, diagnostic.getRange().getStart().getCharacter());
            assertEquals(line, diagnostic.getRange().getEnd().getLine());
            assertEquals(character + values.get(i).length(), diagnostic.getRange().getEnd().getCharacter());
            assertEquals(Severity.WARNING, diagnostic.getSeverity());
            assertEquals(file.getFileName(), diagnostic.getFile());
            assertEquals(file.toUri().toString(), diagnostic.getUri());
            assertTrue(diagnostic.getMessage().contains("'" + values.get(i) + "'"));
            assertTrue(diagnostic.getMessage().contains("'" + attributes.get(i) + "'"));
        }
        assertFalse(result.hasErrors());
    }

    @Test
    void resolvesWorkspaceAliasesAndRetainsExistingFailOpenPolicy() throws IOException {
        write("Kind.java", "package example; interface Kind {}");
        write("Summary.java", "package example; record Summary(String name) {}");
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map">
                        <discriminator javaType=" KIND ">
                            <case value="one" resultType=" Summary "/>
                            <case value="two" resultType="SUMMARY"/>
                            <case value="fqcn" resultType="external.Missing"/>
                            <case value="builtin" resultType="MAP"/>
                            <case value="custom" resultType="registeredAlias"/>
                            <case value="dynamic" resultType="${type}"/>
                            <case value="empty" resultType=" "/>
                            <case value="omitted"/>
                        </discriminator>
                    </resultMap>
                    <resultMap id="Builtin" type="map"><discriminator javaType="STRING"/></resultMap>
                    <resultMap id="Fqcn" type="map"><discriminator javaType="external.Missing"/></resultMap>
                    <resultMap id="Custom" type="map"><discriminator javaType="registeredAlias"/></resultMap>
                    <resultMap id="Dynamic" type="map"><discriminator javaType="${kind}"/></resultMap>
                    <resultMap id="Blank" type="map"><discriminator javaType=" "/></resultMap>
                    <resultMap id="Omitted" type="map"><discriminator/></resultMap>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(result.getDiagnostics().isEmpty());
        assertFalse(result.hasErrors());
    }

    @Test
    void skipsNonDiscriminatorCasesAndElementsOutsideTopLevelResultMaps() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <discriminator javaType="IgnoredKind"><case resultType="IgnoredCase"/></discriminator>
                    <sql id="fragment">
                        <discriminator javaType="IgnoredKind"><case resultType="IgnoredCase"/></discriminator>
                    </sql>
                    <resultMap id="Base" type="map">
                        <case resultType="IgnoredCase"/>
                        <association javaType="map"><case resultType="IgnoredCase"/></association>
                        <discriminator javaType="string">
                            <wrapper><case resultType="IgnoredCase"/></wrapper>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void skipsCaseTypesWhenResultMapAttributeIsPresentButKeepsReferenceChecks() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map">
                        <discriminator javaType="MissingKind">
                            <case value="local" resultMap="Base" resultType="IgnoredCase"/>
                            <case value="missing" resultMap="MissingMap" resultType="IgnoredCase"/>
                            <case value="dynamic" resultMap="${map}" resultType="IgnoredCase"/>
                            <case value="blank" resultMap="" resultType="IgnoredCase"/>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertEquals(1, aliases(result.getDiagnostics()).size());
        assertTrue(aliases(result.getDiagnostics()).get(0).getMessage().contains("MissingKind"));
        assertEquals(1, result.getDiagnostics().stream()
                .filter(d -> RuleIds.UNRESOLVED_DISCRIMINATOR_RESULT_MAP.equals(d.getRuleId())).count());
        assertFalse(result.hasErrors());
    }

    @Test
    void skipsTypesInsideReferencedOrSelectedMappingSubtrees() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map">
                        <discriminator javaType="string">
                            <case value="reference" resultMap="Base" resultType="IgnoredCase">
                                <association javaType="map">
                                    <discriminator javaType="IgnoredKind"><case resultType="IgnoredNested"/></discriminator>
                                </association>
                            </case>
                        </discriminator>
                        <association resultMap="Base">
                            <discriminator javaType="IgnoredKind"><case resultType="IgnoredNested"/></discriminator>
                        </association>
                        <collection select="find">
                            <discriminator javaType="IgnoredKind"><case resultType="IgnoredNested"/></discriminator>
                        </collection>
                        <association select="">
                            <discriminator javaType="IgnoredKind"><case resultType="IgnoredNested"/></discriminator>
                        </association>
                        <collection resultMap="">
                            <discriminator javaType="IgnoredKind"><case resultType="IgnoredNested"/></discriminator>
                        </collection>
                        <discriminator javaType="MissingKind"><case resultType="MissingCase"/></discriminator>
                    </resultMap>
                </mapper>
                """);
        List<Diagnostic> diagnostics = aliases(new AnalyzerCli().run(workspace).getDiagnostics());
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.get(0).getMessage().contains("MissingKind"));
        assertTrue(diagnostics.get(1).getMessage().contains("MissingCase"));
    }

    @Test
    void malformedXmlKeepsOnlyExistingXmlError() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map"><discriminator javaType="MissingKind">
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertEquals(1, result.getDiagnostics().size());
        assertEquals(RuleIds.MALFORMED_XML, result.getDiagnostics().get(0).getRuleId());
        assertTrue(result.hasErrors());
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, contents);
        return file;
    }

    private static List<Diagnostic> aliases(List<Diagnostic> diagnostics) {
        return diagnostics.stream().filter(d -> RuleIds.UNRESOLVED_TYPE_ALIAS.equals(d.getRuleId())).toList();
    }
}
