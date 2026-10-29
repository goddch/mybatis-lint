package com.mybatislint.analyzer.discovery;

import java.nio.file.Path;
import java.util.List;

/**
 * Recursively discovers Java source files under a workspace root, skipping the
 * same directories and paths as mapper XML discovery.
 */
public final class JavaFileScanner {

    private final ExcludePatterns excludes;

    public JavaFileScanner() {
        this(ExcludePatterns.none());
    }

    public JavaFileScanner(ExcludePatterns excludes) {
        this.excludes = excludes;
    }

    public List<Path> scan(Path workspaceRoot) {
        return WorkspaceWalker.findFiles(workspaceRoot, excludes, ".java");
    }

    /** Lombok の設定ファイル(lombok.config)。Lombok が作るコードを変える設定があるかを見るため。 */
    public List<Path> scanLombokConfigs(Path workspaceRoot) {
        return WorkspaceWalker.findFiles(workspaceRoot, excludes, "lombok.config").stream()
                .filter(file -> file.getFileName().toString().equals("lombok.config"))
                .toList();
    }
}
