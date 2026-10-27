package com.mybatislint.analyzer.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Recursively discovers candidate MyBatis mapper XML files under a workspace
 * root, skipping common generated/dependency directories, excluded paths, and
 * anything that cannot be read.
 */
public final class MapperFileScanner {

    /** Directory names that are never descended into. */
    public static final Set<String> IGNORED_DIRECTORY_NAMES = WorkspaceWalker.IGNORED_DIRECTORY_NAMES;

    private final ExcludePatterns excludes;

    public MapperFileScanner() {
        this(ExcludePatterns.none());
    }

    public MapperFileScanner(ExcludePatterns excludes) {
        this.excludes = excludes;
    }

    /**
     * Finds all {@code *.xml} files under {@code workspaceRoot}, in a
     * deterministic (sorted) order, skipping ignored directories.
     */
    public List<Path> scan(Path workspaceRoot) {
        return WorkspaceWalker.findFiles(workspaceRoot, excludes, ".xml");
    }
}
