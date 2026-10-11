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

class MapperDuplicateResultMapIdValidationTest {

    @TempDir
    Path workspace;

    @Test
    void reportsEveryConflictingDeclarationAtItsIdWithoutWorkspaceIndex() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id='Base' type="map"/>
                    <resultMap type="string" id="Base"/>
                    <resultMap
                        id="example.Mapper.Base" type="map"/>
                </mapper>
                """);
        List<Diagnostic> diagnostics = duplicates(new MapperXmlAnalyzer()
                .analyze(file, file.getFileName(), file.toUri().toString()));
        assertEquals(3, diagnostics.size());
        assertEquals(List.of(2, 3, 5), diagnostics.stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        List<String> lines = Files.readAllLines(file);
        for (Diagnostic diagnostic : diagnostics) {
            String line = lines.get(diagnostic.getRange().getStart().getLine() - 1);
            String id = line.contains("example.Mapper.Base") ? "example.Mapper.Base" : "Base";
            assertEquals(line.indexOf(id) + 1, diagnostic.getRange().getStart().getCharacter());
            assertEquals(diagnostic.getRange().getStart().getCharacter(),
                    diagnostic.getRange().getEnd().getCharacter());
            assertEquals(file.getFileName(), diagnostic.getFile());
            assertEquals(file.toUri().toString(), diagnostic.getUri());
            assertEquals(Severity.ERROR, diagnostic.getSeverity());
            assertTrue(diagnostic.getMessage().contains("'" + id + "'"));
            assertTrue(diagnostic.getMessage().contains("'example.Mapper'"));
        }
    }

    @Test
    void ignoresCommentCdataAndProcessingInstructionTextWhenLocatingDeclarations() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <!-- <resultMap id="Base" type="map"/> -->
                    <sql id="fragment"><![CDATA[<resultMap id="Base" type="map"/>]]></sql>
                    <?example <resultMap id="Base" type="map"/> ?>
                    <resultMap id="Base" type="map"/>
                    <!-- <resultMap id="Base" type="map"/> -->
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        List<Diagnostic> diagnostics = duplicates(new MapperXmlAnalyzer()
                .analyze(file, file.getFileName(), file.toUri().toString()));
        assertEquals(List.of(5, 7), diagnostics.stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        for (Diagnostic diagnostic : diagnostics) {
            String line = Files.readAllLines(file).get(diagnostic.getRange().getStart().getLine() - 1);
            assertEquals(line.indexOf("Base") + 1, diagnostic.getRange().getStart().getCharacter());
        }
    }

    @Test
    void detectsSameNamespaceDuplicatesAcrossFilesWithoutBreakingReferences() throws IOException {
        write("First.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map"/>
                    <select id="find" resultMap="Base"/>
                </mapper>
                """);
        write("Second.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="example.Mapper.Base" type="map"/>
                    <resultMap id="Child" type="map" extends="Base"/>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertEquals(2, result.getDiagnostics().size());
        assertEquals(List.of("First.xml", "Second.xml"), duplicates(result.getDiagnostics()).stream()
                .map(d -> d.getFile().toString()).sorted().toList());
        assertTrue(result.hasErrors());
        assertEquals(result.getDiagnostics().stream().map(Diagnostic::getMessage).toList(),
                new AnalyzerCli().run(workspace).getDiagnostics().stream().map(Diagnostic::getMessage).toList());
    }

    @Test
    void distinctNamespacesAndCaseSensitiveIdsDoNotConflict() throws IOException {
        write("First.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map"/>
                    <resultMap id="base" type="map"/>
                    <select id="Base" resultMap="Base"/>
                    <sql id="Base">id</sql>
                </mapper>
                """);
        write("Second.xml", """
                <mapper namespace="other.Mapper"><resultMap id="Base" type="map"/></mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(result.getDiagnostics().isEmpty());
        assertFalse(result.hasErrors());
    }

    @Test
    void skipsMissingBlankDynamicIdsAndNestedDeclarations() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap type="map"/>
                    <resultMap type="map"/>
                    <resultMap id=" " type="map"/>
                    <resultMap id=" " type="map"/>
                    <resultMap id="${id}" type="map"/>
                    <resultMap id="${id}" type="map"/>
                    <resultMap id="Base" type="map">
                        <collection property="items">
                            <resultMap id="Base" type="map"/>
                            <resultMap id="Base" type="map"/>
                        </collection>
                    </resultMap>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void skipsMissingBlankDynamicNamespacesAndNonMapperXml() throws IOException {
        String declarations = "<resultMap id=\"Base\" type=\"map\"/><resultMap id=\"Base\" type=\"map\"/>";
        write("Missing.xml", "<mapper>" + declarations + "</mapper>");
        write("Blank.xml", "<mapper namespace=\" \">" + declarations + "</mapper>");
        write("Dynamic.xml", "<mapper namespace=\"${ns}\">" + declarations + "</mapper>");
        write("Config.xml", "<configuration namespace=\"example.Mapper\">" + declarations + "</configuration>");
        assertTrue(duplicates(new AnalyzerCli().run(workspace).getDiagnostics()).isEmpty());
    }

    @Test
    void malformedXmlIsNotIndexedAsAConflictingDeclaration() throws IOException {
        write("Broken.xml", "<mapper namespace=\"example.Mapper\"><resultMap id=\"Base\" type=\"map\"/>");
        write("Valid.xml", """
                <mapper namespace="example.Mapper"><resultMap id="Base" type="map"/></mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(result.hasErrors());
        assertTrue(duplicates(result.getDiagnostics()).isEmpty());
        assertEquals(1, result.getDiagnostics().size());
        assertEquals(RuleIds.MALFORMED_XML, result.getDiagnostics().get(0).getRuleId());
    }

    private Path write(String name, String xml) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> duplicates(List<Diagnostic> diagnostics) {
        return diagnostics.stream().filter(d -> RuleIds.DUPLICATE_RESULT_MAP_ID.equals(d.getRuleId())).toList();
    }
}
