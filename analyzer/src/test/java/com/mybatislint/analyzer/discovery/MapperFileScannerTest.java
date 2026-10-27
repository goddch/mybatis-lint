package com.mybatislint.analyzer.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void skipsUnreadableDirectoriesInsteadOfFailing(@TempDir Path root) throws IOException {
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        write(root.resolve("mappers/UserMapper.xml"), "<mapper/>");
        Path locked = root.resolve("docker/volume");
        write(locked.resolve("Hidden.xml"), "<mapper/>");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            // root で実行していると権限に関係なく読めてしまい、このテストの前提が成り立たない。
            assumeFalse(Files.isReadable(locked));

            List<Path> found = new MapperFileScanner().scan(root);

            assertEquals(1, found.size(), found::toString);
            assertTrue(found.get(0).toString().endsWith("UserMapper.xml"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void skipsExcludedPaths(@TempDir Path root) throws IOException {
        write(root.resolve("src/main/resources/UserMapper.xml"), "<mapper/>");
        write(root.resolve("docker/data/Skip.xml"), "<mapper/>");
        write(root.resolve("modules/a/volumes/Skip.xml"), "<mapper/>");
        write(root.resolve("modules/a/src/Old.xml"), "<mapper/>");

        List<Path> found = new MapperFileScanner(ExcludePatterns.of(List.of("docker/", "volumes", "**/Old.xml")))
                .scan(root);

        assertEquals(1, found.size(), found::toString);
        assertTrue(found.get(0).toString().endsWith("UserMapper.xml"));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
