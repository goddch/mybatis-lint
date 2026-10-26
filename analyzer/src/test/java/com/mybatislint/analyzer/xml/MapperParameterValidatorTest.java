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

class MapperParameterValidatorTest {

    @Test
    void reportsUndefinedParametersWhenMethodIsResolved(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/UserMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                import org.apache.ibatis.annotations.Param;

                public interface UserMapper {
                    Long findById(@Param("userId") Long userId);
                    String findWithStatus(String status, String name);
                    String findMap(java.util.Map<String, Object> params);
                    String findRequest(UserRequest request);
                }

                class UserRequest {
                    private Long userId;
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">
                        SELECT * FROM users WHERE user_id = #{userId}
                    </select>
                    <select id="findWithStatus">
                        SELECT * FROM users WHERE status = #{status}
                        <if test="name != null">
                            AND name = #{missing}
                        </if>
                    </select>
                    <select id="findRequest">
                        SELECT * FROM users WHERE id = #{request.userId}
                    </select>
                    <select id="findMap">
                        SELECT * FROM users WHERE name = #{missing}
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);

        List<Diagnostic> undefined = diagnostics.stream()
                .filter(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId()))
                .toList();
        assertEquals(1, undefined.size(), () -> diagnostics.toString());
        assertTrue(undefined.get(0).getMessage().contains("missing"));
        assertTrue(undefined.get(0).getMessage().contains("findWithStatus"));
        assertTrue(undefined.get(0).getRange().getStart().getLine() > 0);
    }

    @Test
    void acceptsAliasesBindAndForeachNames(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/LookupMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                public interface LookupMapper {
                    String search(String name, @org.apache.ibatis.annotations.Param("status") String status);
                    String foreachExample(java.util.List<String> items);
                    String bindExample(String userName, String email);
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/LookupMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.LookupMapper">
                    <select id="search">
                        SELECT * FROM users
                        <where>
                            <if test="status != null and param2 != null">
                                AND status = #{status}
                            </if>
                            <if test="name != null">
                                AND name = #{param1}
                            </if>
                        </where>
                    </select>
                    <select id="foreachExample">
                        <foreach collection="items" item="item" index="idx">
                            #{item}
                        </foreach>
                    </select>
                    <select id="bindExample">
                        <bind name="boundName" value="userName" />
                        WHERE name = #{boundName}
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsUndefinedForeachCollectionRoot(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/BatchMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                public interface BatchMapper {
                    java.util.List<String> findByIds(java.util.List<Long> ids);
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/BatchMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.BatchMapper">
                    <select id="findByIds">
                        SELECT * FROM users WHERE id IN
                        <foreach collection="missingIds" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);

        List<Diagnostic> undefined = diagnostics.stream()
                .filter(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId()))
                .toList();
        assertEquals(1, undefined.size(), () -> diagnostics.toString());
        assertTrue(undefined.get(0).getMessage().contains("missingIds"));
        assertTrue(undefined.get(0).getMessage().contains("findByIds"));
        assertTrue(undefined.get(0).getRange().getStart().getLine() > 0);
    }

    @Test
    void acceptsForeachCollectionPropertyPathAndOuterForeachVariables(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/NestedMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                public interface NestedMapper {
                    java.util.List<String> findByRequest(BatchRequest request);
                }

                class BatchRequest {
                    private java.util.List<Group> groups;
                }

                class Group {
                    private java.util.List<Long> ids;
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/NestedMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NestedMapper">
                    <select id="findByRequest">
                        SELECT * FROM users WHERE id IN
                        <foreach collection="request.groups" item="group" index="groupIdx">
                            <foreach collection="group.ids" item="id" open="(" separator="," close=")">
                                #{id}
                            </foreach>
                        </foreach>
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresForeachCollectionWhenParameterIsMapLike(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/MapBatchMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                public interface MapBatchMapper {
                    java.util.List<String> findByIds(java.util.Map<String, Object> params);
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/MapBatchMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.MapBatchMapper">
                    <select id="findByIds">
                        SELECT * FROM users WHERE id IN
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresAmbiguousAndUnresolvedMapperReferences(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/OtherMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                public interface OtherMapper {
                    String findById(String id);
                    String findById(String id, String name);
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/OtherMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.MissingMapper">
                    <select id="findById">
                        SELECT * FROM users WHERE id = #{missing}
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void reportsUndefinedParametersForZeroArgumentMethods(@TempDir Path tempDir) throws IOException {
        // 回帰テスト: resultType 検証を追加した際に JavaMapperIndex から
        // 「引数 0 個のメソッドはインデックスしない」という制限を撤廃した。
        // 引数無しメソッドが index に入るようになっても、既存の undefined-parameter
        // 検証が壊れていない(未定義参照は引き続き検出され、無関係な参照は
        // 誤検知しない)ことを確認する。
        Path javaFile = tempDir.resolve("src/main/java/com/example/UserMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                import java.util.List;

                public interface UserMapper {
                    List<User> findAll();
                }

                class User {
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
        List<Diagnostic> undefined = diagnostics.stream()
                .filter(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId()))
                .toList();
        assertEquals(1, undefined.size(), () -> diagnostics.toString());
    }

    @Test
    void doesNotReportUndefinedParametersForZeroArgumentMethodsWithoutReferences(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/UserMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                import java.util.List;

                public interface UserMapper {
                    List<User> findAll();
                }

                class User {
                }
                """, StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/UserMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">
                        SELECT * FROM users
                    </select>
                </mapper>
                """, StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        List<Diagnostic> diagnostics = new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index);
        assertTrue(diagnostics.stream().noneMatch(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId())),
                () -> diagnostics.toString());
    }

    @Test
    void ignoresJdbcTypeAndOtherParameterMappingOptions(@TempDir Path tempDir) throws IOException {
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    List<Object> findByIds(@Param("ids") List<Long> ids);
                    int update(@Param("name") String name);
                """, """
                    <select id="findByIds">
                        SELECT * FROM users WHERE id IN
                        <foreach collection="ids" item="item" open="(" separator="," close=")">
                            #{item.id,jdbcType=NUMERIC}
                        </foreach>
                    </select>
                    <update id="update">
                        UPDATE users SET name = #{name, javaType=String, jdbcType=VARCHAR}, memo = #{name:VARCHAR}
                    </update>
                """);
        assertTrue(undefined.isEmpty(), () -> undefined.toString());
    }

    @Test
    void reportsUndefinedRootEvenWhenJdbcTypeIsGiven(@TempDir Path tempDir) throws IOException {
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    int update(@Param("name") String name);
                """, """
                    <update id="update">
                        UPDATE users SET name = #{naem,jdbcType=VARCHAR}
                    </update>
                """);
        assertEquals(1, undefined.size(), () -> undefined.toString());
        assertTrue(undefined.get(0).getMessage().contains("'naem'"));
    }

    @Test
    void ignoresStaticMemberReferencesAndLiterals(@TempDir Path tempDir) throws IOException {
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    List<Object> find(@Param("status") String status, @Param("count") int count);
                """, """
                    <select id="find">
                        SELECT * FROM users
                        <where>
                            <if test="status == @jp.example.Const@ACTIVE">AND a = '${@jp.example.Const@ACTIVE}'</if>
                            <if test="status == 'DONE' or status == &quot;HOLD&quot;">AND b = #{status}</if>
                            <if test="count > 0L and count &lt; 0x1F and @@abs(count) > 1.5d">AND c = #{count}</if>
                        </where>
                    </select>
                """);
        assertTrue(undefined.isEmpty(), () -> undefined.toString());
    }

    @Test
    void stillChecksArgumentsOfStaticMethodCalls(@TempDir Path tempDir) throws IOException {
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    List<Object> find(@Param("status") String status);
                """, """
                    <select id="find">
                        SELECT * FROM users
                        <if test="@jp.example.Util@isEmpty(stauts)">WHERE a = 1</if>
                    </select>
                """);
        assertEquals(1, undefined.size(), () -> undefined.toString());
        assertTrue(undefined.get(0).getMessage().contains("'stauts'"));
    }

    @Test
    void skipsSingleUnannotatedBeanOrSimpleParameter(@TempDir Path tempDir) throws IOException {
        // @Param なしの単一引数では、MyBatis は引数オブジェクトそのものを使うので
        // #{name} は Bean のプロパティ(単純型なら値そのもの)を指す。
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    List<Object> findByCondition(Condition condition);
                    List<Object> findByName(String name);
                """, """
                    <select id="findByCondition">
                        SELECT * FROM users WHERE name = #{name}
                        <foreach collection="ids" item="item">#{item}</foreach>
                    </select>
                    <select id="findByName">
                        SELECT * FROM users WHERE name = #{value}
                    </select>
                """);
        assertTrue(undefined.isEmpty(), () -> undefined.toString());
    }

    @Test
    void acceptsBuiltInNamesForSingleUnannotatedCollectionOrArray(@TempDir Path tempDir) throws IOException {
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    List<Object> findByList(List<Long> ids);
                    List<Object> findByCollection(java.util.Collection<Long> ids);
                    List<Object> findByArray(Long[] ids);
                """, """
                    <select id="findByList">
                        SELECT * FROM users WHERE id IN <foreach collection="list" item="id">#{id}</foreach>
                    </select>
                    <select id="findByCollection">
                        SELECT * FROM users WHERE id IN <foreach collection="collection" item="id">#{id}</foreach>
                    </select>
                    <select id="findByArray">
                        SELECT * FROM users WHERE id IN <foreach collection="array" item="id">#{id}</foreach>
                    </select>
                """);
        assertTrue(undefined.isEmpty(), () -> undefined.toString());
    }

    @Test
    void reportsTypoForSingleUnannotatedCollection(@TempDir Path tempDir) throws IOException {
        List<Diagnostic> undefined = analyzeUndefined(tempDir, """
                    List<Object> findByList(List<Long> ids);
                """, """
                    <select id="findByList">
                        SELECT * FROM users WHERE id IN <foreach collection="lsit" item="id">#{id}</foreach>
                    </select>
                """);
        assertEquals(1, undefined.size(), () -> undefined.toString());
        assertTrue(undefined.get(0).getMessage().contains("'lsit'"));
    }

    private static List<Diagnostic> analyzeUndefined(Path tempDir, String methods, String statements)
            throws IOException {
        Path javaFile = tempDir.resolve("src/main/java/com/example/SampleMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                import java.util.List;
                import org.apache.ibatis.annotations.Param;

                public interface SampleMapper {
                %s}

                class Condition {
                    private String name;
                    private List<Long> ids;
                }
                """.formatted(methods), StandardCharsets.UTF_8);

        Path xmlFile = tempDir.resolve("src/main/resources/com/example/SampleMapper.xml");
        Files.createDirectories(xmlFile.getParent());
        Files.writeString(xmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.SampleMapper">
                %s</mapper>
                """.formatted(statements), StandardCharsets.UTF_8);

        JavaMapperIndex index = JavaMapperIndex.build(tempDir);
        return new MapperXmlAnalyzer().analyze(xmlFile, xmlFile.getFileName(), xmlFile.toUri().toString(), index)
                .stream()
                .filter(d -> RuleIds.UNDEFINED_PARAMETER.equals(d.getRuleId()))
                .toList();
    }
}
