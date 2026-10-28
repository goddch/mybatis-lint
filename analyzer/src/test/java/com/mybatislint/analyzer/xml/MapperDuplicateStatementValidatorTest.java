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

/** 同じ namespace の別々のファイルにある、同じ id の statement を見つける。 */
class MapperDuplicateStatementValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void reportsTheFirstDeclarationInEachFile() throws IOException {
        Path first = write("UserMapper.xml", """
                <mapper namespace="example.UserMapper">
                    <select id="findById">SELECT 1</select>
                    <select id="findAll">SELECT 1</select>
                </mapper>
                """);
        Path second = write("UserMapperExt.xml", """
                <mapper namespace="example.UserMapper">
                    <select id="example.UserMapper.findById">SELECT 2</select>
                </mapper>
                """);

        List<Diagnostic> errors = duplicates();
        assertEquals(2, errors.size(), errors::toString);
        assertTrue(errors.stream().allMatch(d -> d.getSeverity() == Severity.ERROR));
        assertEquals(first.toUri().toString(), errors.get(0).getUri());
        assertEquals(2, errors.get(0).getRange().getStart().getLine());
        assertTrue(errors.get(0).getMessage().contains("UserMapperExt.xml"));
        assertEquals(second.toUri().toString(), errors.get(1).getUri());
        String line = Files.readAllLines(second).get(1);
        assertEquals(line.indexOf("example.UserMapper.findById") + 1, errors.get(1).getRange().getStart().getCharacter());
    }

    @Test
    void skipsDifferentNamespacesDatabaseIdsAndSameFileDuplicates() throws IOException {
        write("A.xml", """
                <mapper namespace="example.A">
                    <select id="find">SELECT 1</select>
                    <select id="byDb" databaseId="oracle">SELECT 1 FROM dual</select>
                    <select id="local">SELECT 1</select>
                    <select id="local">SELECT 2</select>
                </mapper>
                """);
        write("B.xml", """
                <mapper namespace="example.B">
                    <select id="find">SELECT 1</select>
                </mapper>
                """);
        write("A2.xml", """
                <mapper namespace="example.A">
                    <select id="byDb" databaseId="postgresql">SELECT 1</select>
                </mapper>
                """);

        // 同じファイルの中の重複は、これまでどおり MapperXmlAnalyzer が 1 件だけ報告する。
        List<Diagnostic> errors = duplicates();
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.get(0).getMessage().contains("'local'"));
    }

    private List<Diagnostic> duplicates() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.DUPLICATE_STATEMENT_ID.equals(d.getRuleId()))
                .toList();
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.writeString(file, contents);
        return file;
    }
}
