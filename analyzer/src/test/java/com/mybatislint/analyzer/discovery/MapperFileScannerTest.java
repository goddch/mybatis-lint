package com.mybatislint.analyzer.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperFileScannerTest {

    @Test
    void findsXmlFilesButSkipsIgnoredDirectories(@TempDir Path root) throws IOException {
        write(root.resolve("mappers/UserMapper.xml"), "<mapper/>");
        write(root.resolve("build/generated/Skip.xml"), "<mapper/>");
        write(root.resolve(".git/objects/Skip.xml"), "<mapper/>");
        write(root.resolve("node_modules/pkg/Skip.xml"), "<mapper/>");
        write(root.resolve("target/Skip.xml"), "<mapper/>");
        write(root.resolve(".gradle/Skip.xml"), "<mapper/>");
        write(root.resolve("README.md"), "not xml");

        List<Path> found = new MapperFileScanner().scan(root);

        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().endsWith("UserMapper.xml"));
    }

    @Test
    void returnsEmptyListWhenRootDoesNotExist(@TempDir Path root) {
        List<Path> found = new MapperFileScanner().scan(root.resolve("missing"));
        assertTrue(found.isEmpty());
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
