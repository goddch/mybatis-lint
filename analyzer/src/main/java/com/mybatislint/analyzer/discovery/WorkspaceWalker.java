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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mapper XML と Java ソースの探索で共通の、ワークスペースの走査処理。
 *
 * <p>読めないディレクトリやファイルは飛ばし、飛ばしたパスを stderr に出す。Docker のボリュームのように
 * 権限のないディレクトリがワークスペースにあっても、解析全体を止めないためである。
 * そうした場所に mapper があることはまず考えにくく、読めないものは判定もできない。</p>
 */
final class WorkspaceWalker {

    /** Directory names that are never descended into. */
    static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", "build", "target", "node_modules");

    private static final Set<Path> REPORTED_UNREADABLE = ConcurrentHashMap.newKeySet();

    private WorkspaceWalker() {
    }

    /** 拡張子が {@code suffix}(小文字)のファイルを、パス順に並べて返す。 */
    static List<Path> findFiles(Path workspaceRoot, ExcludePatterns excludes, String suffix) {
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(workspaceRoot)) {
            return results;
        }
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(workspaceRoot)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (IGNORED_DIRECTORY_NAMES.contains(name)
                            || excludes.matches(workspaceRoot.relativize(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(suffix)
                            && Files.isReadable(file)
                            && !excludes.matches(workspaceRoot.relativize(file))) {
                        results.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 権限がなく中を開けないディレクトリなどがここに来る。
                    // stdout は JSON 専用なので、何を飛ばしたかは stderr に出す(拡張機能のログに表示される)。
                    // XML と Java で 2〜3 回走査するため、同じパスは 1 回だけ出す。
                    if (REPORTED_UNREADABLE.add(file.toAbsolutePath().normalize())) {
                        System.err.println("Skipped unreadable path: " + workspaceRoot.relativize(file));
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
