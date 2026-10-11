package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
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
 * {@code mybatis-lint/result-type-mismatch} の要件(問題文の「テスト」節)に
 * 列挙された最小ケースだけを確認するテスト。
 */
class MapperResultTypeValidatorTest {

    @Test
    void acceptsMatchingSingleObjectResultType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User findById(Long id);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findById" resultType="User">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void acceptsMatchingListResultType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                import java.util.List;

                public interface UserMapper {
                    List<User> findAll();
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll" resultType="User">
                        SELECT * FROM users
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsMismatchedSingleObjectResultType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User findById(Long id);
                }

                class User {
                }

                class Order {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findById" resultType="Order">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        List<Diagnostic> mismatches = diagnostics.stream()
                .filter(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId()))
                .toList();
        assertEquals(1, mismatches.size(), () -> diagnostics.toString());
    }

    @Test
    void skipsStatementsWithResultMap(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User findById(Long id);
                }

                class User {
                }

                class Order {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="orderMap" type="Order">
                    </resultMap>
                    <select id="findById" resultMap="orderMap" resultType="Order">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void skipsDynamicResultTypeAlias(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User findById(Long id);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findById" resultType="${resultTypeAlias}">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void acceptsMatchingBuiltInAliasForPrimitiveReturnType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    int count();
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="count" resultType="integer">
                        SELECT COUNT(*) FROM users
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void acceptsMatchingBuiltInAliasForBoxedReturnType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    Long getId();
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="getId" resultType="long">
                        SELECT id FROM users
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsMismatchedBuiltInAlias(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User findById(Long id);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findById" resultType="int">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertEquals(1, diagnostics.stream()
                .filter(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId()))
                .count(), () -> diagnostics.toString());
    }

    @Test
    void acceptsMatchingBuiltInMapAliasForMapReturnType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                import java.util.Map;

                public interface UserMapper {
                    Map<String, Object> findAsMap(Long id);
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findAsMap" resultType="map">
                        SELECT id, name FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsMismatchedBuiltInMapAlias(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                public interface UserMapper {
                    User findById(Long id);
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findById" resultType="map">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        assertEquals(1, diagnostics.stream()
                .filter(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId()))
                .count(), () -> diagnostics.toString());
    }

    @Test
    void skipsNestedGenericMapReturnType(@TempDir Path tempDir) throws IOException {
        writeJavaMapper(tempDir, """
                package com.example;

                import java.util.List;
                import java.util.Map;

                public interface UserMapper {
                    Map<String, List<User>> findGrouped();
                }

                class User {
                }
                """);

        List<Diagnostic> diagnostics = analyze(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findGrouped" resultType="map">
                        SELECT dept_id, user_name FROM users
                    </select>
                </mapper>
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.RESULT_TYPE_MISMATCH.equals(d.getRuleId())),
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
