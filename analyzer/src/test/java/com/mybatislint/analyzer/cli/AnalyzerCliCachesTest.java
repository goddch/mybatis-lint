package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** --server で解析をまたいで読み込み結果を使い回しても、変わったファイルは読み直す。 */
class AnalyzerCliCachesTest {

    @TempDir
    Path workspace;

    @Test
    void picksUpChangedJavaAndXmlFilesAndForgetsDeletedOnes() throws IOException {
        Path mapperJava = write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { void find(); }
                """);
        Path mapperXml = write("src/main/resources/com/example/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);
        AnalyzerCli cli = new AnalyzerCli();
        AnalyzerCli.Caches caches = new AnalyzerCli.Caches();
        assertEquals(List.of(), ruleIds(cli.run(workspace, caches)));

        // Java だけ変える: XML にない statement を指すメソッドが増える。
        rewrite(mapperJava, """
                package com.example;
                public interface UserMapper { void find(); void findAll(); }
                """);
        assertEquals(List.of(RuleIds.MISSING_MAPPER_STATEMENT), ruleIds(cli.run(workspace, caches)));

        // XML だけ変える: 足りなかった statement を足す。
        rewrite(mapperXml, """
                <mapper namespace="com.example.UserMapper">
                    <select id="find" resultType="map">SELECT 1 FROM dual</select>
                    <select id="findAll" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);
        assertEquals(List.of(), ruleIds(cli.run(workspace, caches)));

        // 消したファイルの内容は残らない(findAll は消したファイルにしかなかった)。
        Files.delete(mapperXml);
        write("src/main/resources/com/example/UserMapperMoved.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find" resultType="map">SELECT 1 FROM dual</select>
                </mapper>
                """);
        assertEquals(List.of(RuleIds.MISSING_MAPPER_STATEMENT), ruleIds(cli.run(workspace, caches)));
        // キャッシュを使っても、使わない解析と同じ結果になる。
        assertEquals(ruleIds(cli.run(workspace)), ruleIds(cli.run(workspace, caches)));
    }

    private static List<String> ruleIds(AnalyzerCli.Result result) {
        return result.getDiagnostics().stream().map(Diagnostic::getRuleId).sorted().toList();
    }

    /** 同じ時刻のうちに書き換えても変更とわかるよう、更新日時を進める。 */
    private static void rewrite(Path file, String contents) throws IOException {
        FileTime before = Files.getLastModifiedTime(file);
        Files.writeString(file, contents);
        Files.setLastModifiedTime(file, FileTime.from(before.toInstant().plusSeconds(1).isAfter(Instant.now())
                ? before.toInstant().plusSeconds(1) : Instant.now()));
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
