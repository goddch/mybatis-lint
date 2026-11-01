package com.mybatislint.analyzer.discovery;

import java.nio.file.Path;
import java.util.List;

/**
 * 接続先の DB を推定する手がかりになる、設定ファイルとビルドファイルを探す。
 * ({@code application.properties} / {@code *.yml}、{@code pom.xml}、{@code build.gradle} など)。
 * XML の設定ファイル(mybatis-config.xml など)は mapper XML の探索で見つかるので、ここでは探さない。
 */
public final class ProjectSettingsScanner {

    private final ExcludePatterns excludes;

    public ProjectSettingsScanner(ExcludePatterns excludes) {
        this.excludes = excludes;
    }

    public List<Path> scan(Path workspaceRoot) {
        return WorkspaceWalker.findFiles(workspaceRoot, excludes, name -> name.endsWith(".properties")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts"));
    }
}
