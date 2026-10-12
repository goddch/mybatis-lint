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

class MapperResultMapInheritanceCycleValidationTest {

    @TempDir
    Path workspace;

    @Test
    void reportsSelfReferencesAtTheExtendsValueWithoutWorkspaceIndex() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Self" type="map" extends="Self"/>
                    <resultMap id="example.Mapper.Qualified" type="map"
                        extends="example.Mapper.Qualified"/>
                </mapper>
                """);
        List<Diagnostic> diagnostics = cycles(new MapperXmlAnalyzer()
                .analyze(file, file.getFileName(), file.toUri().toString()));
        assertEquals(2, diagnostics.size());
        Diagnostic diagnostic = diagnostics.get(0);
        assertEquals(file.getFileName(), diagnostic.getFile());
        assertEquals(file.toUri().toString(), diagnostic.getUri());
        assertEquals(2, diagnostic.getRange().getStart().getLine());
        String line = Files.readAllLines(file).get(1);
        assertEquals(line.lastIndexOf("Self") + 1, diagnostic.getRange().getStart().getCharacter());
        assertEquals(4, diagnostics.get(1).getRange().getStart().getLine());
        assertTrue(diagnostics.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
        assertTrue(diagnostic.getMessage().contains("Self"));
        assertFalse(new AnalyzerCli().run(workspace).hasErrors());
    }

    @Test
    void reportsOnlyCycleMembersNotIncomingDescendants() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Leaf" type="map" extends="A"/>
                    <resultMap id="A" type="map" extends="example.Mapper.B"/>
                    <resultMap id="example.Mapper.B" type="map" extends="C"/>
                    <resultMap id="C" type="map" extends="A"/>
                    <resultMap id="Valid" type="map"/>
                    <resultMap id="Child" type="map" extends="Valid"/>
                </mapper>
                """);
        List<Diagnostic> diagnostics = cycles(new AnalyzerCli().run(workspace).getDiagnostics());
        assertEquals(List.of(3, 4, 5), diagnostics.stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        assertEquals(3, cycles(new MapperXmlAnalyzer()
                .analyze(file, file.getFileName(), file.toUri().toString())).size());
    }

    @Test
    void detectsCyclesAcrossNamespacesAndSameNamespaceFilesRegardlessOfFileOrder() throws IOException {
        Path a = write("A.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="A" type="map" extends="other.Mapper.B"/>
                </mapper>
                """);
        Path b = write("B.xml", """
                <mapper namespace="other.Mapper">
                    <resultMap id="B" type="map" extends="example.Mapper.C"/>
                </mapper>
                """);
        Path c = write("C.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="C" type="map" extends="A"/>
                </mapper>
                """);
        assertEquals(3, cycles(new AnalyzerCli().run(workspace).getDiagnostics()).size());
        for (List<Path> files : List.of(List.of(a, b, c), List.of(c, b, a))) {
            MapperResultMapIndex index = MapperResultMapIndex.build(files);
            assertTrue(index.isInInheritanceCycle("example.Mapper.A"));
            assertTrue(index.isInInheritanceCycle("other.Mapper.B"));
            assertTrue(index.isInInheritanceCycle("example.Mapper.C"));
        }
    }

    @Test
    void skipsAmbiguousDuplicateIdsAndPathsThroughThem() throws IOException {
        Path a = write("A.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="A" type="map" extends="B"/>
                    <resultMap id="B" type="map" extends="A"/>
                    <resultMap id="Self" type="map" extends="Self"/>
                    <resultMap id="Self" type="map" extends="Self"/>
                </mapper>
                """);
        Path b = write("B.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="example.Mapper.B" type="map"/>
                </mapper>
                """);
        assertTrue(cycles(new AnalyzerCli().run(workspace).getDiagnostics()).isEmpty());
        for (List<Path> files : List.of(List.of(a, b), List.of(b, a))) {
            assertFalse(MapperResultMapIndex.build(files).isInInheritanceCycle("example.Mapper.A"));
        }
    }

    @Test
    void skipsUnknownDynamicNestedAndMalformedDeclarations() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Missing" type="map" extends="Unknown"/>
                    <resultMap id="Dynamic" type="map" extends="${parent}"/>
                    <resultMap id="${id}" type="map" extends="${id}"/>
                    <resultMap type="map" extends="example.Mapper."/>
                    <resultMap id="Blank" type="map" extends=""/>
                    <resultMap id="Case" type="map" extends="case"/>
                    <resultMap id="case" type="map"/>
                    <resultMap id="Base" type="map">
                        <association property="nested">
                            <resultMap id="Nested" type="map" extends="Nested"/>
                        </association>
                    </resultMap>
                </mapper>
                """);
        write("DynamicNamespace.xml", """
                <mapper namespace="${namespace}">
                    <resultMap id="Self" type="map" extends="Self"/>
                </mapper>
                """);
        write("NoNamespace.xml", """
                <mapper>
                    <resultMap id="Self" type="map" extends="Self"/>
                </mapper>
                """);
        write("Broken.xml", "<mapper namespace=\"broken.Mapper\"><resultMap");
        write("Config.xml", "<configuration><resultMap id=\"Self\" extends=\"Self\"/></configuration>");
        assertTrue(cycles(new AnalyzerCli().run(workspace).getDiagnostics()).isEmpty());
    }

    @Test
    void handlesLongChainsWithoutRecursiveTraversal() throws IOException {
        StringBuilder xml = new StringBuilder("<mapper namespace=\"example.Mapper\">");
        for (int i = 0; i < 10000; i++) {
            xml.append("<resultMap id=\"Map").append(i).append("\" extends=\"Map")
                    .append(i + 1).append("\"/>");
        }
        xml.append("<resultMap id=\"Map10000\" extends=\"Map9999\"/></mapper>");
        Path file = write("Mapper.xml", xml.toString());
        MapperResultMapIndex index = MapperResultMapIndex.build(List.of(file));
        assertFalse(index.isInInheritanceCycle("example.Mapper.Map0"));
        assertTrue(index.isInInheritanceCycle("example.Mapper.Map9999"));
        assertTrue(index.isInInheritanceCycle("example.Mapper.Map10000"));
    }

    private Path write(String name, String xml) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> cycles(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(d -> RuleIds.RESULT_MAP_INHERITANCE_CYCLE.equals(d.getRuleId()))
                .toList();
    }
}
