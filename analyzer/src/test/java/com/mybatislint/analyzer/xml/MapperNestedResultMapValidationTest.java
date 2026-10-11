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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperNestedResultMapValidationTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesLocalForwardAndNamespaceQualifiedReferences() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <association property="local" resultMap=" Base "/>
                        <collection property="qualified" resultMap="example.Mapper.Base"/>
                    </resultMap>
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        assertTrue(nestedDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void resolvesReferencesAcrossMapperFilesAndWithinNestedMappings() throws IOException {
        write("Shared.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Shared" type="map"/>
                </mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <association property="child" resultMap="Shared">
                            <collection property="children" resultMap="example.Mapper.Shared"/>
                        </association>
                    </resultMap>
                </mapper>
                """);
        assertTrue(nestedDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void reportsMissingAssociationAndCollectionReferencesAtAttributeValues() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <association property="child" resultMap='MissingChild'/>
                        <collection property="children" resultMap="other.Mapper.MissingChildren"/>
                    </resultMap>
                </mapper>
                """);
        List<Diagnostic> warnings = nestedDiagnostics(new AnalyzerCli().run(workspace));
        assertEquals(2, warnings.size());
        assertEquals(List.of(3, 4), warnings.stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        List<String> lines = Files.readAllLines(file);
        assertEquals(lines.get(2).indexOf("MissingChild") + 1,
                warnings.get(0).getRange().getStart().getCharacter());
        assertEquals(lines.get(3).indexOf("other.Mapper.MissingChildren") + 1,
                warnings.get(1).getRange().getStart().getCharacter());
        assertEquals("Mapper.xml", warnings.get(0).getFile().toString());
        assertEquals(file.toUri().toString(), warnings.get(0).getUri());
        assertTrue(warnings.get(0).getMessage().contains("MissingChild"));
        assertTrue(warnings.get(1).getMessage().contains("other.Mapper.MissingChildren"));
        assertTrue(warnings.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
    }

    @Test
    void skipsBlankDynamicAndCommaSeparatedReferencesAndNonMappingElements() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <association property="blank" resultMap=" "/>
                        <collection property="dynamic" resultMap="${mapping}"/>
                        <association property="multiple" resultMap="One, Two"/>
                        <id property="id" resultMap="Missing"/>
                    </resultMap>
                    <sql id="fragment">
                        <association property="notAMapping" resultMap="Missing"/>
                    </sql>
                </mapper>
                """);
        assertTrue(nestedDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void skipsReferencesWhenNamespaceIsMissingOrDynamic() throws IOException {
        write("Missing.xml", """
                <mapper>
                    <resultMap id="Parent" type="map">
                        <association property="child" resultMap="Missing"/>
                    </resultMap>
                </mapper>
                """);
        write("Dynamic.xml", """
                <mapper namespace="${namespace}">
                    <resultMap id="Parent" type="map">
                        <collection property="children" resultMap="Missing"/>
                    </resultMap>
                </mapper>
                """);
        assertTrue(nestedDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    private Path write(String name, String xml) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> nestedDiagnostics(AnalyzerCli.Result result) {
        return result.getDiagnostics().stream()
                .filter(d -> RuleIds.UNRESOLVED_NESTED_RESULT_MAP.equals(d.getRuleId()))
                .toList();
    }
}
