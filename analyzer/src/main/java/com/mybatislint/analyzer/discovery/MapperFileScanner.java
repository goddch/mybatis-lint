package com.mybatislint.analyzer.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Recursively discovers candidate MyBatis mapper XML files under a workspace
 * root, skipping common generated/dependency directories.
 */
public final class MapperFileScanner {

    /** Directory names that are never descended into. */
    public static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", "build", "target", "node_modules");

    /**
     * Finds all {@code *.xml} files under {@code workspaceRoot}, in a
     * deterministic (sorted) order, skipping ignored directories.
     */
    public List<Path> scan(Path workspaceRoot) {
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(workspaceRoot)) {
            return results;
        }
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(workspaceRoot) && IGNORED_DIRECTORY_NAMES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
                        results.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        results.sort(Comparator.comparing(Path::toString));
        return results;
    }
}
