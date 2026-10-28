package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code #{...}} で、引数の Bean にないプロパティを読もうとしているものを見つける。 */
class MapperParameterPropertyTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void writeDomainClasses() throws IOException {
        write("src/main/java/com/example/BaseDomain.java", """
                package com.example;
                import lombok.Data;
                @Data
                public abstract class BaseDomain { private String createdBy; }
                """);
        write("src/main/java/com/example/UserDomain.java", """
                package com.example;
                import lombok.Data;
                @Data
                public class UserDomain extends BaseDomain {
                    private Long userId;
                    private String userName;
                    private boolean isActive;
                    public String getDisplayName() { return userName; }
                }
                """);
    }

    @Test
    void reportsUnknownPropertiesOfASingleBeanParameter() throws IOException {
        writeMapper("""
                int insertUser(UserDomain user);
                """, """
                <insert id="insertUser">
                    INSERT INTO users VALUES (#{userId}, #{userNmae,jdbcType=VARCHAR}, #{user.userId})
                    <if test="stauts != null"/>
                </insert>
                """);

        List<Diagnostic> errors = propertyDiagnostics();
        assertEquals(List.of("stauts", "user", "userNmae"), names(errors).stream().sorted().toList(),
                errors::toString);
        assertTrue(errors.stream().allMatch(d -> d.getSeverity() == Severity.ERROR));
        assertTrue(errors.stream().allMatch(d -> d.getMessage().contains("com.example.UserDomain")));
    }

    @Test
    void acceptsReadableLombokInheritedAndGetterProperties() throws IOException {
        writeMapper("""
                int insertUser(UserDomain user);
                """, """
                <insert id="insertUser">
                    <bind name="pattern" value="'%' + userName + '%'"/>
                    INSERT INTO users VALUES (#{userId}, #{active}, #{isActive}, #{createdBy},
                        #{displayName}, #{pattern}, #{_parameter.userId})
                    <if test="userName != null and isActive"/>
                </insert>
                """);

        assertTrue(propertyDiagnostics().isEmpty(), () -> propertyDiagnostics().toString());
    }

    @Test
    void reportsUnknownPropertiesOfNamedParameters() throws IOException {
        writeMapper("""
                int updateUser(@Param("user") UserDomain user, @Param("id") Long id);
                """, """
                <update id="updateUser">
                    UPDATE users SET a = #{user.userNmae}, b = #{param1.active}, c = #{user.userName.length}
                    WHERE id = #{id}
                </update>
                """);

        List<Diagnostic> errors = propertyDiagnostics();
        assertEquals(List.of("userNmae"), names(errors), errors::toString);
        assertTrue(errors.get(0).getMessage().contains("parameter 'user'"));
    }

    @Test
    void skipsTypesWhoseRuntimePropertiesAreUncertain() throws IOException {
        write("src/main/java/com/example/Types.java", """
                package com.example;
                class Parent { private String a; }
                class Kid extends Parent { private String b; }
                class FromFramework extends org.springframework.Base { private String a; }
                @lombok.experimental.Accessors(prefix = "m") class Prefixed { private String mName; }
                """);
        writeMapper("""
                int byParent(Parent value);
                int byAbstract(BaseDomain value);
                int byFramework(FromFramework value);
                int byPrefixed(Prefixed value);
                int byString(String value);
                int byUnknown(com.other.Dto value);
                int byNamed(@Param("p") Parent p, @Param("q") String q);
                int byShadowed(@Param("user") UserDomain user, @Param("users") java.util.List<Object> users);
                """, """
                <select id="byParent">SELECT #{missing}</select>
                <select id="byAbstract">SELECT #{missing}</select>
                <select id="byFramework">SELECT #{missing}</select>
                <select id="byPrefixed">SELECT #{missing}</select>
                <select id="byString">SELECT #{anyName}</select>
                <select id="byUnknown">SELECT #{missing}</select>
                <select id="byNamed">SELECT #{p.missing}, #{q.length}</select>
                <select id="byShadowed">
                    <foreach collection="users" item="user">#{user.anything}</foreach>
                </select>
                """);

        // 子クラスがある Parent や抽象クラスは、実行時に渡るオブジェクトがもっとプロパティを持ちうる。
        assertTrue(propertyDiagnostics().isEmpty(), () -> propertyDiagnostics().toString());
    }

    private List<Diagnostic> propertyDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.UNKNOWN_PARAMETER_PROPERTY.equals(d.getRuleId()))
                .toList();
    }

    private static List<String> names(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.getMessage().replaceAll("^Unknown property '([^']+)'.*", "$1")).toList();
    }

    private void writeMapper(String methods, String statements) throws IOException {
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                %s}
                """.formatted(methods));
        write("src/main/resources/com/example/UserMapper.xml",
                "<mapper namespace=\"com.example.UserMapper\">\n" + statements + "</mapper>\n");
    }

    private void write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
