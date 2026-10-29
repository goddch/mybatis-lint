package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 打ち間違えた名前の候補(「もしかして」)。 */
class NameSuggestionsTest {

    @TempDir
    Path workspace;

    @Test
    void suggestsTheOnlyCloseName() {
        List<String> names = List.of("userId", "userName", "mailAddress");
        assertEquals("userName", NameSuggestions.closest("userNmae", names));
        assertEquals("userName", NameSuggestions.closest("usrName", names));
        assertEquals("mailAddress", NameSuggestions.closest("mailadress", names));
        assertEquals("userId", NameSuggestions.closest("userid", names));
    }

    @Test
    void doesNotGuessWhenNothingIsCloseOrTwoNamesAreEquallyClose() {
        assertNull(NameSuggestions.closest("displayName", List.of("userId", "userName")));
        // userNam は userName にも userNum にも 1 文字違い。どちらか決められない。
        assertNull(NameSuggestions.closest("userNam", List.of("userName", "userNum")));
        // 短い名前は、大文字小文字の違いしか直さない。
        assertNull(NameSuggestions.closest("ip", List.of("id")));
        assertEquals("id", NameSuggestions.closest("ID", List.of("id")));
        // MyBatis の組み込みの名前(_parameter など)は候補にしない。
        assertNull(NameSuggestions.closest("parameter", List.of("_parameter")));
    }

    @Test
    void comparesColumnAliasesIgnoringUnderscores() {
        assertEquals("userName", NameSuggestions.closest("USER_NMAE", List.of("userId", "userName"),
                MapperSelectAliasValidator::normalize));
    }

    @Test
    void attachesTheSuggestionToTheDiagnostic() throws IOException {
        write("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    User find(@Param("userId") Long userId, @Param("status") String status);
                }
                """);
        write("src/main/java/com/example/User.java", """
                package com.example;
                public class User { private Long userId; private String userName; }
                """);
        write("src/main/resources/com/example/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find" resultType="com.example.User">
                        SELECT u.ID AS userId, u.NAME AS userNmae FROM users u
                         WHERE u.ID = #{usreId} AND u.STATUS = #{status}
                    </select>
                </mapper>
                """);

        List<Diagnostic> diagnostics = new AnalyzerCli().run(workspace).getDiagnostics();
        Diagnostic alias = find(diagnostics, RuleIds.UNKNOWN_SELECT_ALIAS);
        assertEquals("userName", alias.getSuggestion());
        assertTrue(alias.getMessage().endsWith("Did you mean 'userName'?"), alias.getMessage());
        Diagnostic parameter = find(diagnostics, RuleIds.UNDEFINED_PARAMETER);
        assertEquals("userId", parameter.getSuggestion());
        // 波線は打ち間違えた名前そのものを指す(この範囲を候補で置き換える)。
        assertEquals(parameter.getRange().getStart().getCharacter() + "usreId".length(),
                parameter.getRange().getEnd().getCharacter());
    }

    private static Diagnostic find(List<Diagnostic> diagnostics, String ruleId) {
        return diagnostics.stream().filter(d -> ruleId.equals(d.getRuleId())).findFirst().orElseThrow();
    }

    private void write(String name, String contents) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
