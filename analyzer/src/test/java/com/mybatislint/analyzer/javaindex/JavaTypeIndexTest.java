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
    void resolvesSimplePropertyTypesAndSkipsAmbiguousShapes(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "com/example/Address.java",
                "package com.example; public class Address { private String street; }");
        writeJavaFile(tempDir, "com/other/Detail.java",
                "package com.other; public class Detail {}");
        writeJavaFile(tempDir, "com/example/User.java", """
                package com.example;
                import com.other.Detail;
                public class User {
                    private Address address;
                    private com.example.Address qualified;
                    private Detail imported;
                    private Address[] array;
                    private java.util.List<Address> list;
                    private Address conflict;
                    public void setConflict(Detail value) {}
                    public void setSetterOnly(Address value) {}
                    public void setOverloaded(Address value) {}
                    public void setOverloaded(Detail value) {}
                    public void setOverloaded(com.example.Address value) {}
                    public <Address> void setGeneric(Address value) {}
                    public void setVarargs(Address... values) {}
                    private static Address shared;
                    private Missing missing;
                }
                """);

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        for (String property : Set.of("address", "qualified", "setterOnly")) {
            assertEquals("com.example.Address", index.findResultPropertyType("com.example.User", property));
        }
        assertEquals("com.other.Detail", index.findResultPropertyType("com.example.User", "imported"));
        for (String property : Set.of("array", "list", "conflict", "overloaded", "generic", "varargs",
                "shared", "missing", "unknown")) {
            assertNull(index.findResultPropertyType("com.example.User", property), property);
        }
        assertNull(index.findResultPropertyType("User", "address"));
        assertNull(index.findResultPropertyType(null, "address"));
    }

    @Test
    void skipsInferenceForWildcardImportsShadowingAndDuplicateTypes(@TempDir Path tempDir) throws IOException {
        writeJavaFile(tempDir, "com/example/Address.java", "package com.example; class Address {}");
        writeJavaFile(tempDir, "com/example/Wildcard.java", """
                package com.example;
                import com.other.*;
                class Wildcard { Address address; com.example.Address qualified; }
                """);
        writeJavaFile(tempDir, "com/example/StaticImport.java", """
                package com.example;
                import static com.other.Outer.Address;
                class StaticImport { Address address; }
                """);
        writeJavaFile(tempDir, "com/example/Shadow.java", """
                package com.example;
                class Shadow { class Address {} Address address; }
                """);
        writeJavaFile(tempDir, "first/User.java", """
                package com.example; class User { Address address; }
                """);
        writeJavaFile(tempDir, "second/User.java", "package com.example; class User {}");
        writeJavaFile(tempDir, "com/example/Duplicate.java", """
                package com.example; class Duplicate { User user; }
                """);

        JavaTypeIndex index = JavaTypeIndex.build(tempDir);

        for (String owner : Set.of("Wildcard", "StaticImport", "Shadow", "User")) {
            assertNull(index.findResultPropertyType("com.example." + owner, "address"), owner);
        }
        assertNull(index.findResultPropertyType("com.example.Duplicate", "user"));
        assertEquals("com.example.Address", index.findResultPropertyType("com.example.Wildcard", "qualified"));
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
