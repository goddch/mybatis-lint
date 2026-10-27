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

class MapperStatementResultMapValidationTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesLocalForwardAndNamespaceQualifiedDeclarationsWithoutWorkspaceIndex() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <select id="local" resultMap="Base">select 1</select>
                    <select id="qualified" resultMap="example.Mapper.Base">select 1</select>
                    <select id="padded" resultMap=" Base ">select 1</select>
                    <select id="paddedQualified" resultMap=" example.Mapper.Base ">select 1</select>
                    <resultMap id="example.Mapper.Base" type="map"/>
                </mapper>
                """);
        assertTrue(new MapperXmlAnalyzer().analyze(file, file.getFileName(), file.toUri().toString()).isEmpty());
    }

    @Test
    void resolvesQualifiedAndSameNamespaceReferencesAcrossFiles() throws IOException {
        write("Other.xml", """
                <mapper namespace="other.Mapper">
                    <resultMap id="other.Mapper.Base" type="map"/>
                </mapper>
                """);
        write("Shared.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Shared" type="map"/>
                </mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <select id="shared" resultMap="Shared">select 1</select>
                    <select id="find" resultMap="other.Mapper.Base">select 1</select>
                </mapper>
                """);
        assertTrue(new AnalyzerCli().run(workspace).getDiagnostics().isEmpty());
    }

    @Test
    void reportsEachMissingReferenceAtTheAttributeValue() throws IOException {
        // other.Mapper はワークスペースにあるが、参照先の resultMap は宣言していない。
        write("Other.xml", "<mapper namespace=\"other.Mapper\"/>");
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Base" type="map"/>
                    <select id="find" resultMap='Missing'>select 1</select>
                    <select id="remote" resultMap="other.Mapper.Missing">select 1</select>
                    <select id="next" resultMap="NextMissing">select 1</select>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        List<Diagnostic> warnings = statementDiagnostics(result);
        assertEquals(3, warnings.size());
        assertEquals(List.of(3, 4, 5), warnings.stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        String line = Files.readAllLines(file).get(2);
        assertEquals(line.indexOf("Missing") + 1, warnings.get(0).getRange().getStart().getCharacter());
        assertEquals("Mapper.xml", warnings.get(0).getFile().toString());
        assertEquals(file.toUri().toString(), warnings.get(0).getUri());
        assertTrue(warnings.get(0).getMessage().contains("'Missing'"));
        assertTrue(warnings.get(1).getMessage().contains("'other.Mapper.Missing'"));
        assertTrue(warnings.get(2).getMessage().contains("'NextMissing'"));
        assertTrue(warnings.stream().allMatch(d -> d.getSeverity() == Severity.WARNING));
        assertFalse(result.hasErrors());
    }

    @Test
    void checksAllTopLevelStatementKindsWithoutDuplicatingResultTypeWarning() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <select id="find" resultMap="Missing">select 1</select>
                    <select id="both" resultMap="Missing" resultType="map">select 1</select>
                    <insert id="add" resultMap="Missing">insert</insert>
                    <update id="change" resultMap="Missing">update</update>
                    <delete id="remove" resultMap="Missing">delete</delete>
                </mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertEquals(4, statementDiagnostics(result).size());
        assertEquals(List.of(2, 4, 5, 6), statementDiagnostics(result).stream()
                .map(d -> d.getRange().getStart().getLine()).toList());
        assertEquals(1, result.getDiagnostics().stream()
                .filter(d -> RuleIds.RESULT_MAP_AND_TYPE.equals(d.getRuleId())).count());
    }

    @Test
    void skipsBlankAndDynamicReferencesAndDoesNotCheckNestedReferencesAsStatements() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <select id="absent">select 1</select>
                    <select id="blank" resultMap=" "/>
                    <select id="dynamic" resultMap="Missing, ${maps}"/>
                    <select id="typeOnly" resultType="map"/>
                    <resultMap id="Base" type="map">
                        <association property="child" resultMap="Missing"/>
                        <collection property="children" resultMap="Missing"/>
                    </resultMap>
                    <sql id="fragment"><select id="nested" resultMap="Missing"/></sql>
                </mapper>
                """);
        assertTrue(statementDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void doesNotResolveTypeAliasesSqlFragmentsNestedDeclarationsOrOtherNamespaceShortIds() throws IOException {
        write("Other.xml", """
                <mapper namespace="other.Mapper">
                    <resultMap id="Base" type="map"/>
                </mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <sql id="Columns">id</sql>
                    <resultMap id="Outer" type="map">
                        <collection property="items"><resultMap id="Nested" type="map"/></collection>
                    </resultMap>
                    <select id="alias" resultMap="map">select 1</select>
                    <select id="fragment" resultMap="Columns">select 1</select>
                    <select id="nestedMap" resultMap="Nested">select 1</select>
                    <select id="short" resultMap="Base">select 1</select>
                </mapper>
                """);
        assertEquals(4, statementDiagnostics(new AnalyzerCli().run(workspace)).size());
    }

    @Test
    void skipsCommaSeparatedListsIncludingMissingReferences() throws IOException {
        write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <resultMap id="Local" type="map"/>
                    <select id="localFirst" resultMap="Local, Remote"/>
                    <select id="qualifiedFirst" resultMap="other.Mapper.Base, Remote"/>
                    <select id="qualifiedLater" resultMap="Remote, other.Mapper.Base"/>
                    <select id="emptyEntries" resultMap=","/>
                    <select id="trailingComma" resultMap="Missing,"/>
                </mapper>
                """);
        assertTrue(statementDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void duplicateLocalIdsStillResolveToTheFirstDeclaration() throws IOException {
        Path file = write("Mapper.xml", """
                <mapper namespace="example.Mapper">
                    <select id="find" resultMap="Base"/>
                    <select id="qualified" resultMap="example.Mapper.Base"/>
                    <resultMap id="Base" type="map"/>
                    <resultMap id="Base" type="string"/>
                </mapper>
                """);
        assertTrue(new MapperXmlAnalyzer().analyze(file, file.getFileName(), file.toUri().toString()).stream()
                .noneMatch(d -> RuleIds.UNRESOLVED_STATEMENT_RESULT_MAP.equals(d.getRuleId())));
        assertTrue(statementDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void skipsMissingAndDynamicNamespacesAndNonMapperXml() throws IOException {
        write("Missing.xml", "<mapper><select id=\"find\" resultMap=\"Missing\"/></mapper>");
        write("Dynamic.xml", "<mapper namespace=\"${ns}\"><select id=\"find\" resultMap=\"Missing\"/></mapper>");
        write("Config.xml", "<configuration><select id=\"find\" resultMap=\"Missing\"/></configuration>");
        assertTrue(statementDiagnostics(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void malformedXmlDoesNotPreventWorkspaceResolution() throws IOException {
        write("Broken.xml", "<mapper namespace=\"broken.Mapper\"><resultMap");
        write("Base.xml", """
                <mapper namespace="example.Mapper"><resultMap id="Base" type="map"/></mapper>
                """);
        write("Mapper.xml", """
                <mapper namespace="example.Mapper"><select id="find" resultMap="Base"/></mapper>
                """);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        assertTrue(result.hasErrors());
        assertTrue(statementDiagnostics(result).isEmpty());
    }

    private Path write(String name, String xml) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> statementDiagnostics(AnalyzerCli.Result result) {
        return result.getDiagnostics().stream()
                .filter(d -> RuleIds.UNRESOLVED_STATEMENT_RESULT_MAP.equals(d.getRuleId()))
                .toList();
    }
}
