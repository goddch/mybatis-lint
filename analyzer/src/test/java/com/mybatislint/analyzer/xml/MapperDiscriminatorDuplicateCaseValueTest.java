package com.mybatislint.analyzer.xml;

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

class MapperDiscriminatorDuplicateCaseValueTest {

    @TempDir
    Path workspace;

    @Test
    void reportsLaterDuplicatesAtValueAttributeIncludingNestedDiscriminators() throws IOException {
        Path file = write("""
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="a" resultType="map"/>
                            <case value="b" resultType="map"/>
                            <case value="a" resultType="map"/>
                        </discriminator>
                        <collection property="items" ofType="map">
                            <discriminator column="kind" javaType="string">
                                <case value="a" resultType="map"/>
                                <case value="a" resultType="map"/>
                            </discriminator>
                        </collection>
                    </resultMap>
                </mapper>
                """);
        List<Diagnostic> found = duplicates(file);
        assertEquals(2, found.size());
        assertEquals(Severity.WARNING, found.get(0).getSeverity());
        assertEquals(6, found.get(0).getRange().getStart().getLine());
        assertEquals(11, found.get(1).getRange().getStart().getLine());
    }

    @Test
    void skipsBlankDynamicDistinctAndOutsideContexts() throws IOException {
        Path file = write("""
                <mapper namespace="example.Mapper">
                    <resultMap id="Parent" type="map">
                        <discriminator column="kind" javaType="string">
                            <case value="" resultType="map"/>
                            <case value="" resultType="map"/>
                            <case value="${x}" resultType="map"/>
                            <case value="${x}" resultType="map"/>
                            <case value="a" resultType="map"/>
                        </discriminator>
                        <discriminator column="kind" javaType="string">
                            <case value="a" resultType="map"/>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        assertTrue(duplicates(file).isEmpty());
    }

    private Path write(String xml) throws IOException {
        Path file = workspace.resolve("Mapper.xml");
        Files.writeString(file, xml);
        return file;
    }

    private static List<Diagnostic> duplicates(Path file) {
        return new MapperXmlAnalyzer().analyze(file, file.getFileName(), file.toUri().toString()).stream()
                .filter(d -> RuleIds.DUPLICATE_DISCRIMINATOR_CASE_VALUE.equals(d.getRuleId()))
                .toList();
    }
}
