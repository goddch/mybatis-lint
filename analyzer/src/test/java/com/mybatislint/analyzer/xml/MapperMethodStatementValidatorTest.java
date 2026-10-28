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

/** Java の Mapper メソッドに、対応する statement が XML にないものを見つける。 */
class MapperMethodStatementValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void reportsMethodsWithoutStatementAtTheMethodName() throws IOException {
        Path java = write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Select;
                public interface UserMapper {
                    User findById(Long id);
                    java.util.List<User> findByNmae(String name);
                    @Select("SELECT COUNT(*) FROM users")
                    int countAll();
                    default int countTwice() { return countAll() * 2; }
                    static int zero() { return 0; }
                }
                """);
        write("src/main/resources/com/example/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById" resultType="map">SELECT 1</select>
                    <select id="findByName" resultType="map">SELECT 1</select>
                </mapper>
                """);
        // 同じ namespace の mapper が別ファイルにあってもまとめて見る。
        write("src/main/resources/com/example/UserMapperExtra.xml", """
                <mapper namespace="com.example.UserMapper">
                    <update id="com.example.UserMapper.unused">UPDATE t SET a = 1</update>
                </mapper>
                """);

        AnalyzerCli.Result result = new AnalyzerCli().run(workspace);
        List<Diagnostic> warnings = missing(result);
        assertEquals(1, warnings.size(), warnings::toString);
        Diagnostic warning = warnings.get(0);
        assertEquals(Severity.WARNING, warning.getSeverity());
        assertEquals(Path.of("src/main/java/com/example/UserMapper.java"), warning.getFile());
        assertEquals(java.toUri().toString(), warning.getUri());
        String line = Files.readAllLines(java).get(4);
        assertEquals(5, warning.getRange().getStart().getLine());
        assertEquals(line.indexOf("findByNmae") + 1, warning.getRange().getStart().getCharacter());
        assertEquals(line.indexOf("findByNmae") + 1 + "findByNmae".length(), warning.getRange().getEnd().getCharacter());
        assertTrue(warning.getMessage().contains("'findByNmae'"));
        assertFalse(result.hasErrors());
    }

    @Test
    void skipsMappersWhoseStatementsCannotBeKnown() throws IOException {
        // XML がワークスペースにない(注釈だけ、または別モジュール)。
        write("src/main/java/com/example/AnnotationOnlyMapper.java", """
                package com.example;
                public interface AnnotationOnlyMapper { int missing(); }
                """);
        // 子インターフェースの XML で実装されうる。
        write("src/main/java/com/example/BaseMapper.java", """
                package com.example;
                public interface BaseMapper<T> { T missing(Long id); }
                """);
        write("src/main/java/com/example/OrderMapper.java", """
                package com.example;
                public interface OrderMapper extends BaseMapper<Object> { }
                """);
        write("src/main/resources/com/example/BaseMapper.xml", "<mapper namespace=\"com.example.BaseMapper\"/>");
        write("src/main/resources/com/example/OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                    <select id="missing" resultType="map">SELECT 1</select>
                </mapper>
                """);

        assertTrue(missing(new AnalyzerCli().run(workspace)).isEmpty());
    }

    @Test
    void skipsOnlyTheNamespaceOfABrokenMapperXml() throws IOException {
        // 編集中で壊れた XML があっても、ほかの namespace のチェックは続ける。
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { int missing(); }
                """);
        write("src/main/java/com/example/OrderMapper.java", """
                package com.example;
                public interface OrderMapper { int missing(); }
                """);
        write("src/main/resources/com/example/UserMapper.xml", "<mapper namespace=\"com.example.UserMapper\"/>");
        write("src/main/resources/com/example/Broken.xml", "<mapper namespace=\"com.example.UserMapper\">");
        write("src/main/resources/com/example/OrderMapper.xml", "<mapper namespace=\"com.example.OrderMapper\"/>");

        List<Diagnostic> warnings = missing(new AnalyzerCli().run(workspace));
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).getFile().toString().endsWith("OrderMapper.java"));
    }

    @Test
    void keepsCheckingWhenAnotherMapperHasNoNamespace() throws IOException {
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { int missing(); }
                """);
        write("src/main/resources/com/example/UserMapper.xml", "<mapper namespace=\"com.example.UserMapper\"/>");
        write("src/main/resources/com/example/NoNamespace.xml", "<mapper><select id=\"missing\">SELECT 1</select></mapper>");

        assertEquals(1, missing(new AnalyzerCli().run(workspace)).size());
    }

    @Test
    void skipsEverythingWhenABrokenMapperXmlHasNoReadableNamespace() throws IOException {
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { int missing(); }
                """);
        write("src/main/resources/com/example/UserMapper.xml", "<mapper namespace=\"com.example.UserMapper\"/>");
        write("src/main/resources/com/example/Broken.xml", "<mapper><select id=\"missing\">");

        assertTrue(missing(new AnalyzerCli().run(workspace)).isEmpty());
    }

    private static List<Diagnostic> missing(AnalyzerCli.Result result) {
        return result.getDiagnostics().stream()
                .filter(d -> RuleIds.MISSING_MAPPER_STATEMENT.equals(d.getRuleId()))
                .toList();
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
