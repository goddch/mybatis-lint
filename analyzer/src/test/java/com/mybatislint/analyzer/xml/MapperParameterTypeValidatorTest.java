package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mybatis-lint/parameter-type-mismatch} の正常系・異常系・フェイルオープン系を
 * まとめて確認するテスト。要件(問題文の「テスト」節)に列挙されたケースを
 * それぞれ独立したテストメソッドとして書き起こしている。
 */
class MapperParameterTypeValidatorTest {

    @Test
    void acceptsMatchingSimpleNameParameterType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(User user);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="User">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsMismatchedSimpleNameParameterType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(User user);
                }

                class User {
                }

                class Order {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="Order">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        List<Diagnostic> mismatches = diagnostics.stream()
                .filter(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId()))
                .toList();
        assertEquals(1, mismatches.size(), () -> diagnostics.toString());
        assertEquals(Severity.WARNING, mismatches.get(0).getSeverity());
        assertTrue(mismatches.get(0).getMessage().contains("Order"));
        assertTrue(mismatches.get(0).getMessage().contains("User"));
        assertTrue(mismatches.get(0).getRange().getStart().getLine() > 0);
    }

    @Test
    void acceptsPrimitiveLongMatchingBuiltinAlias(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(long id);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="long">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    /**
     * 設計判断: MyBatis の {@code TypeAliasRegistry} は "long" alias を実際には
     * {@code Long.class}(boxed)に登録しているため、boxed の {@code Long} 引数と
     * "long" alias は本来 "primitive と boxed の違いを気にせず一致" とみなしてよい。
     * このルールは「primitive と boxed の厳密な区別」を目的にしておらず、
     * むしろその違いで誤検知を出す方が実害が大きい({@code Long} 引数を持つ
     * 一般的な Mapper メソッドで無用な warning が大量発生する)ため、
     * 明示的に OK 扱いにする設計とした。
     */
    @Test
    void acceptsBoxedLongMatchingBuiltinAliasByDesign(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(Long id);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="long">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void acceptsFullyQualifiedClassNameParameterType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(User user);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="com.example.User">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresMultiArgumentMethods(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(String name, String status);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="User">
                        SELECT * FROM users WHERE name = #{param1} AND status = #{param2}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresZeroArgumentMethods(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    java.util.List<User> findAll();
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll" parameterType="User">
                        SELECT * FROM users
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresUnresolvedMapperMethod(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface OtherMapper {
                    User find(User user);
                }

                class User {
                }
                """);

        // namespace が Java 側のどのインターフェースにも解決できないケース。
        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.MissingMapper">
                    <select id="find" parameterType="Order">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresAmbiguousOverloadedMethod(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(User user);
                    User find(User user, String status);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="Order">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresDynamicParameterTypeAlias(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(User user);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="${parameterTypeAlias}">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsMismatchedBuiltinAliasAgainstUnrelatedClass(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User find(User user);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="string">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        List<Diagnostic> mismatches = diagnostics.stream()
                .filter(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId()))
                .toList();
        assertEquals(1, mismatches.size(), () -> diagnostics.toString());
    }

    @Test
    void acceptsListAliasMatchingListParameter(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    java.util.List<User> findByIds(java.util.List<Long> ids);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findByIds" parameterType="list">
                        SELECT * FROM users WHERE id IN
                        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresUnknownCollectionLikeCustomClassForListAlias(@TempDir Path tempDir) throws IOException {
        // "IdBatch" は List/Map/Set 系の命名規則に沿っていない独自クラス名。
        // 本当に List を実装しているかどうかは静的には分からないため、
        // 誤検知を避けてフェイルオープンする設計になっていることを確認する。
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    java.util.List<User> findByIds(IdBatch ids);
                }

                class User {
                }

                class IdBatch {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findByIds" parameterType="list">
                        SELECT * FROM users WHERE id IN #{ids}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresGenericTypeVariableParameter(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    <T> T find(T criteria);
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="find" parameterType="User">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.PARAMETER_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    private static void writeJavaMapper(Path tempDir, String source) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/UserMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);
    }

    private static List<Diagnostic> analyze(Path tempDir, String xmlContent) throws IOException {
        Path xmlFile = tempDir.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, xmlContent, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        return new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
    }
}
