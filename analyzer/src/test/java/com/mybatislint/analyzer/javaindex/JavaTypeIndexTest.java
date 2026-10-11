package com.mybatislint.analyzer.javaindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTypeIndexTest {

    @Test
    void collectsClassAndInterfaceSimpleNamesCaseInsensitively(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "com/example/User.java", """
                package com.example;

                public class User {
                }
                """);
        writeJavaFile(tempDir, "com/example/UserMapper.java", """
                package com.example;

                public interface UserMapper {
                }
                """);

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        assertTrue(index.containsSimpleName("User"));
        // MyBatis の alias 解決は大文字小文字を区別しないため、索引側の比較も
        // 大文字小文字を無視する必要がある。
        assertTrue(index.containsSimpleName("user"));
        assertTrue(index.containsSimpleName("USERMAPPER"));
        assertFalse(index.containsSimpleName("UserAlias"));
    }

    @Test
    void collectsRecordSimpleNames(@TempDir Path tempDir) throws IOException {
        // record は DTO/Entity としてよく使われるため、ClassOrInterfaceDeclaration
        // だけを見ていると見落として誤検知(false positive)につながる。
        writeJavaFile(tempDir, "com/example/UserSummary.java", """
                package com.example;

                public record UserSummary(Long id, String name) {
                }
                """);

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        assertTrue(index.containsSimpleName("UserSummary"));
    }

    @Test
    void emptyIndexContainsNothing() {
        JavaTypeIndex index = JavaTypeIndex.empty();
        assertTrue(index.isEmpty());
        assertFalse(index.containsSimpleName("Anything"));
        assertFalse(index.containsSimpleName(""));
        assertFalse(index.containsSimpleName(null));
        assertNull(index.findResultProperties("com.example.Unknown"));
    }

    @Test
    void collectsFieldsAndSetterPropertiesForQualifiedClasses(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "com/example/User.java", """
                package com.example;
                public class User {
                    private Long id;
                    private String firstName, lastName;
                    public void setDisplayName(String value) {}
                    public void setURL(String value) {}
                    public void setX(String value) {}
                    public void set() {}
                    public void setIgnored(String a, String b) {}
                    public String getReadOnly() { return ""; }
                }
                """);

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        assertEquals(Set.of("id", "firstName", "lastName", "displayName", "URL", "x"),
                index.findResultProperties("com.example.User"));
        assertNull(index.findResultProperties("User"));
        assertNull(index.findResultProperties("com.example.user"));
    }

    @Test
    void skipsPropertiesOfUnsupportedTypes(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "com/example/Types.java", """
                package com.example;
                class Base { private Long id; }
                class Child extends Base {}
                interface Contract { void setName(String name); }
                class Implementation implements Contract { public void setName(String name) {} }
                abstract class AbstractDto {}
                class GenericDto<T> {}
                @Data class GeneratedDto {}
                class DelegatingDto { @Delegate private Base base; }
                record Summary(Long id) {}
                class Outer { static class Nested {} }
                """);

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        for (String name : Set.of("Child", "Contract", "Implementation", "AbstractDto", "GenericDto",
                "GeneratedDto", "DelegatingDto", "Summary", "Outer.Nested")) {
            assertNull(index.findResultProperties("com.example." + name), name);
        }
        assertEquals(Set.of("id"), index.findResultProperties("com.example.Base"));
    }

    @Test
    void skipsDuplicateQualifiedClasses(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "first/User.java", "package com.example; class User { Long id; }");
        writeJavaFile(tempDir, "second/User.java", "package com.example; class User { String name; }");

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        assertTrue(index.containsSimpleName("User"));
        assertNull(index.findResultProperties("com.example.User"));
    }

    @Test
    void skipsClassesWithDuplicateRecordQualifiedNames(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "first/User.java", "package com.example; class User { Long id; }");
        writeJavaFile(tempDir, "second/User.java", "package com.example; record User(String name) {}");

        assertNull(JavaTypeIndex.build(tempDir).findResultProperties("com.example.User"));
    }

    private static void writeJavaFile(Path tempDir, String relativePath, String content) throws IOException {
        Path file = tempDir.resolve("src/main/java/" + relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
