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

class MapperResultMapExtendsValidationTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesLocalForwardAndQualifiedParentsWithoutWorkspaceIndex() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Leaf" type="map" extends="Middle"/>
                    <resultMap id="Middle" type="map" extends="example.Mapper.Base"/>
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        assertTrue(new MapperXmlAnalyzer().analyze(file, file.getFileName(), file.toUri().toString()).isEmpty());
    }

    @Test
    void resolvesDeclarationsAlreadyQualifiedWithTheirNamespace() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="example.Mapper.Base" type="map"/>
                    <resultMap id="Leaf" type="map" extends="Base"/>
                    <resultMap id="OtherLeaf" type="map" extends="example.Mapper.Base"/>
                </mapper>
                """);
        assertTrue(new MapperXmlAnalyzer().analyze(file, file.getFileName(), file.toUri().toString()).isEmpty());
        write("Other.xml", """
                <mapper namespace="other.Mapper">
                    <resultMap id="Leaf" type="map" extends="example.Mapper.Base"/>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void reportsMissingParentsAtTheirAttributeValues() throws IOException {
        // other.Mapper はワークスペースにあるが、参照先の resultMap は宣言していない。
        write("Other.xml", "<mapper namespace=\"other.Mapper\"/>");
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Empty" type="map" extends=""/>
                    <resultMap id="Dynamic" type="map" extends="${parent}"/>
                    <resultMap id="Missing" type="map" extends="NoSuchParent"/>
                    <resultMap id="Remote" type="map" extends="other.Mapper.Missing"/>
                </mapper>
                """);
        List<Diagnostic> warnings = extendsDiagnostics(new AnalyzerCli().run(workspace));
        assertEquals(2, warnings.size());
        assertEquals("Mapper.xml", warnings.get(0).getFile().toString());
        assertEquals(file.toUri().toString(), warnings.get(0).getUri());
        assertEquals(4, warnings.get(0).getRange().getStart().getLine());
        assertEquals(5, warnings.get(1).getRange().getStart().getLine());
        String line = Files.readAllLines(file).get(3);
        assertEquals(line.indexOf("NoSuchParent") + 1, warnings.get(0).getRange().getStart().getCharacter());
        assertTrue(warnings.get(0).getMessage().contains("NoSuchParent"));
        assertTrue(warnings.get(1).getMessage().contains("other.Mapper.Missing"));
        assertTrue(warnings.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
        assertTrue(!new AnalyzerCli().run(workspace).hasErrors());
    }

    @Test
    void resolvesParentsAcrossFilesAndSameNamespaceFiles() throws IOException {
        write("Parent.xml", """
                <mapper namespace="other.Mapper">
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        write("Shared.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Shared" type="map"/>
                </mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Middle" type="map" extends="other.Mapper.Base"/>
                    <resultMap id="Leaf" type="map" extends="Middle"/>
                    <resultMap id="Sibling" type="map" extends="Shared"/>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void reportsOnlyTheBrokenLinkInAnInheritanceChain() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Leaf" type="map" extends="Middle"/>
                    <resultMap id="Middle" type="map" extends="MissingBase"/>
                </mapper>
                """);
        List<Diagnostic> warnings = extendsDiagnostics(new AnalyzerCli().run(workspace));
        assertEquals(1, warnings.size());
        assertEquals(3, warnings.get(0).getRange().getStart().getLine());
        assertTrue(warnings.get(0).getMessage().contains("MissingBase"));
    }

    @Test
    void doesNotResolveSqlFragmentsOrOtherNamespaceShortIds() throws IOException {
        write("Parent.xml", """
                <mapper namespace="other.Mapper">
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <sql id="Columns">id</sql>
                    <resultMap id="Leaf" type="map" extends="Columns"/>
                    <resultMap id="Other" type="map" extends="Base"/>
                </mapper>
                """);
        assertEquals(2, extendsDiagnostics(new AnalyzerCli().run(workspace)).size());
    }

    @Test
    void skipsUnknownNamespacesAndNestedResultMapDeclarations() throws IOException {
        write("MissingNamespace.xml", """
                <mapper>
                    <resultMap id="Leaf" type="map" extends="Missing"/>
                </mapper>
                """);
        write("DynamicNamespace.xml", """
                <mapper namespace="${namespace}">
                    <resultMap id="Leaf" type="map" extends="Missing"/>
                </mapper>
                """);
        write("Nested.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map">
                        <collection property="items">
                            <resultMap id="Nested" type="map" extends="Missing"/>
                        </collection>
                    </resultMap>
                </mapper>
                """);
        assertTrue(extendsDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void malformedFilesDoNotPreventOtherParentResolution() throws IOException {
        write("Broken.xml", "<mapper namespace=\"broken.Mapper\"><resultMap");
        write("Config.xml", "<configuration><resultMap id=\"Base\"/></configuration>");
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Leaf" type="map" extends="Base"/>
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(result.hasErrors());
        assertTrue(extendsDiagnostics(result).isEmpty());
    }

    private Path write(String name, String xml) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> extendsDiagnostics(AnalyzerCli.Result result) {
        return result.getDiagnostics().stream()
                .filter(d -> RuleIds.UNRESOLVED_RESULT_MAP_EXTENDS.equals(d.getRuleId()))
                .toList();
    }
}
