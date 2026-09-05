package com.mybatislint.analyzer.javaindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void emptyIndexContainsNothing() {
        JavaTypeIndex index = JavaTypeIndex.empty();
        assertTrue(index.isEmpty());
        assertFalse(index.containsSimpleName("Anything"));
        assertFalse(index.containsSimpleName(""));
        assertFalse(index.containsSimpleName(null));
    }

    private static void writeJavaFile(Path tempDir, String relativePath, String content) throws IOException {
        Path file = tempDir.resolve("src/main/java/" + relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
