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

/** 引数なしのコンストラクタがない resultType で、列が位置でコンストラクタに渡されるもの。 */
class MapperConstructorMappingValidatorTest {

    private static final String FINAL_DATA = """
            package com.example;
            import lombok.Data;
            @Data
            public class UserDomain {
                private static final long serialVersionUID = 1L;
                private final Long userId;
                private final String userName;
                private final String mailAddress;
            }
            """;

    @TempDir
    Path workspace;

    @Test
    void reportsAFieldThatOnlyGetsAValueByPosition() throws IOException {
        writeDomain(FINAL_DATA);
        Path file = writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId, u.USER_NAME AS userNmae, u.MAIL AS mailAddress FROM users u
                </select>
                """);

        List<Diagnostic> diagnostics = constructorDiagnostics();
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        Diagnostic warning = diagnostics.get(0);
        assertEquals(RuleIds.CONSTRUCTOR_POSITIONAL_BINDING, warning.getRuleId());
        assertEquals(Severity.WARNING, warning.getSeverity());
        assertTrue(warning.getMessage().contains("'com.example.UserDomain.userName'"), warning.getMessage());
        assertTrue(warning.getMessage().contains("column 'userNmae'"), warning.getMessage());
        String line = Files.readAllLines(file).get(2);
        assertEquals(3, warning.getRange().getStart().getLine());
        assertEquals(line.indexOf("com.example.UserDomain") + 1, warning.getRange().getStart().getCharacter());
    }

    @Test
    void reportsTooFewColumnsAsAnError() throws IOException {
        writeDomain(FINAL_DATA);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId, u.USER_NAME AS userName FROM users u
                </select>
                """);

        List<Diagnostic> diagnostics = constructorDiagnostics();
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        assertEquals(RuleIds.CONSTRUCTOR_COLUMN_COUNT, diagnostics.get(0).getRuleId());
        assertEquals(Severity.ERROR, diagnostics.get(0).getSeverity());
        assertTrue(diagnostics.get(0).getMessage().contains("3-argument constructor"));
        assertTrue(diagnostics.get(0).getMessage().contains("only 2 column(s)"));
    }

    @Test
    void acceptsColumnsInAnyOrderWhenEveryFieldHasANamedColumn() throws IOException {
        // 位置で入った値は、名前の一致する列で上書きされる。列が多くても余りは捨てられる。
        writeDomain(FINAL_DATA);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.MAIL AS mail_address, u.USER_ID AS USERID, u.USER_NAME AS userName, u.AGE AS age
                      FROM users u
                </select>
                """);

        assertEquals(List.of(), constructorDiagnostics());
    }

    @Test
    void usesAllNonStaticFieldsExceptInitializedFinalOnesForAllArgsConstructor() throws IOException {
        writeDomain("""
                package com.example;
                import lombok.AllArgsConstructor;
                import lombok.Getter;
                @Getter
                @AllArgsConstructor
                public class UserDomain {
                    private final String kind = "user";
                    private Long userId;
                    private String userName;
                }
                """);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId, u.NAME AS name FROM users u
                </select>
                """);

        List<Diagnostic> diagnostics = constructorDiagnostics();
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        assertTrue(diagnostics.get(0).getMessage().contains("UserDomain.userName' gets the value of column 'name'"));
    }

    @Test
    void treatsValueAsAnAllArgsConstructor() throws IOException {
        writeDomain("""
                package com.example;
                import lombok.Value;
                @Value
                public class UserDomain {
                    Long userId;
                    String userName;
                }
                """);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId FROM users u
                </select>
                """);

        assertEquals(List.of(RuleIds.CONSTRUCTOR_COLUMN_COUNT), ruleIds(constructorDiagnostics()));
    }

    @Test
    void onlyCountsColumnsForAConstructorWrittenInTheSource() throws IOException {
        // 本体が引数をどのフィールドに入れるかわからないので、位置による警告は出さない。
        writeDomain("""
                package com.example;
                public class UserDomain {
                    private final Long userId;
                    private final String userName;
                    public UserDomain(Long id, String name) { this.userId = id; this.userName = name; }
                }
                """);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS id, u.NAME AS name FROM users u
                </select>
                <select id="findId" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId FROM users u
                </select>
                """);

        assertEquals(List.of(RuleIds.CONSTRUCTOR_COLUMN_COUNT), ruleIds(constructorDiagnostics()));
    }

    @Test
    void skipsClassesWithANoArgConstructor() throws IOException {
        writeDomain("""
                package com.example;
                import lombok.AllArgsConstructor;
                import lombok.Data;
                import lombok.NoArgsConstructor;
                @Data
                @NoArgsConstructor(force = true)
                @AllArgsConstructor
                public class UserDomain {
                    private final Long userId;
                    private final String userName;
                }
                """);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userNo FROM users u
                </select>
                """);

        assertEquals(List.of(), constructorDiagnostics());
    }

    @Test
    void skipsDataClassesWithoutFinalFields() throws IOException {
        // @Data の required-args コンストラクタは引数なしになる。
        writeDomain("""
                package com.example;
                import lombok.Data;
                @Data
                public class UserDomain {
                    private Long userId;
                    private String userName;
                }
                """);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userNo FROM users u
                </select>
                """);

        assertEquals(List.of(), constructorDiagnostics());
    }

    @Test
    void skipsClassesWithSeveralConstructorsOrUnpredictableLombokAnnotations() throws IOException {
        // コンストラクタが 2 つあると、MyBatis は列の型で選ぶ。@Builder はコンストラクタを足すことがある。
        write("src/main/java/com/example/TwoConstructors.java", """
                package com.example;
                public class TwoConstructors {
                    private final Long userId;
                    public TwoConstructors(Long userId) { this.userId = userId; }
                    public TwoConstructors(String userId) { this.userId = Long.valueOf(userId); }
                }
                """);
        write("src/main/java/com/example/Built.java", """
                package com.example;
                import lombok.Builder;
                import lombok.Data;
                @Data
                @Builder
                public class Built {
                    private final Long userId;
                    private final String userName;
                }
                """);
        writeMapper("""
                <select id="findTwo" resultType="com.example.TwoConstructors">
                    SELECT 1 AS a, 2 AS b FROM dual
                </select>
                <select id="findBuilt" resultType="com.example.Built">
                    SELECT 1 AS a FROM dual
                </select>
                """);

        assertEquals(List.of(), constructorDiagnostics());
    }

    @Test
    void skipsLombokClassesWhenLombokConfigAddsAPrivateNoArgConstructor() throws IOException {
        writeDomain(FINAL_DATA);
        write("lombok.config", """
                config.stopBubbling = true
                lombok.noArgsConstructor.extraPrivate = true
                """);
        writeMapper("""
                <select id="find" resultType="com.example.UserDomain">
                    SELECT u.USER_ID AS userId FROM users u
                </select>
                """);

        assertEquals(List.of(), constructorDiagnostics());
    }

    @Test
    void skipsSelectsWhoseColumnsAreNotKnown() throws IOException {
        writeDomain(FINAL_DATA);
        writeMapper("""
                <sql id="columns">u.USER_ID AS userId</sql>
                <select id="findAll" resultType="com.example.UserDomain">
                    SELECT * FROM users u
                </select>
                <select id="findIncluded" resultType="com.example.UserDomain">
                    SELECT <include refid="columns"/> FROM users u
                </select>
                <select id="findMapped" resultMap="userMap">
                    SELECT u.USER_ID AS userId FROM users u
                </select>
                <resultMap id="userMap" type="com.example.UserDomain"/>
                """);

        assertEquals(List.of(), constructorDiagnostics());
    }

    private static List<String> ruleIds(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::getRuleId).toList();
    }

    private List<Diagnostic> constructorDiagnostics() {
        return new AnalyzerCli().run(workspace).getDiagnostics().stream()
                .filter(d -> RuleIds.CONSTRUCTOR_COLUMN_COUNT.equals(d.getRuleId())
                        || RuleIds.CONSTRUCTOR_POSITIONAL_BINDING.equals(d.getRuleId()))
                .toList();
    }

    private void writeDomain(String source) throws IOException {
        write("src/main/java/com/example/UserDomain.java", source);
    }

    private Path writeMapper(String statements) throws IOException {
        return write("src/main/resources/com/example/UserMapper.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"com.example.UserMapper\">\n"
                        + statements + "</mapper>\n");
    }

    private Path write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }
}
