package com.mybatislint.analyzer.discovery;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcludePatternsTest {

    @Test
    void patternWithoutSlashMatchesNameAtAnyDepth() {
        ExcludePatterns excludes = ExcludePatterns.of(List.of("docker-data"));
        assertTrue(excludes.matches(Path.of("docker-data")));
        assertTrue(excludes.matches(Path.of("a", "b", "docker-data")));
        assertFalse(excludes.matches(Path.of("docker-data-old")));
    }

    @Test
    void patternWithSlashMatchesFromWorkspaceRoot() {
        ExcludePatterns excludes = ExcludePatterns.of(List.of("docker/volumes"));
        assertTrue(excludes.matches(Path.of("docker", "volumes")));
        assertFalse(excludes.matches(Path.of("other", "docker", "volumes")));
    }

    @Test
    void trailingSlashAndDoubleStarMeanTheDirectoryItself() {
        for (String glob : List.of("docker/", "docker/**", "./docker", "/docker")) {
            assertTrue(ExcludePatterns.of(List.of(glob)).matches(Path.of("docker")), glob);
        }
    }

    @Test
    void wildcards() {
        ExcludePatterns excludes = ExcludePatterns.of(List.of("modules/*/generated", "**/legacy", "tmp?"));
        assertTrue(excludes.matches(Path.of("modules", "a", "generated")));
        assertFalse(excludes.matches(Path.of("modules", "a", "b", "generated")));
        assertTrue(excludes.matches(Path.of("legacy")));
        assertTrue(excludes.matches(Path.of("x", "y", "legacy")));
        assertTrue(excludes.matches(Path.of("tmp1")));
        assertFalse(excludes.matches(Path.of("tmp12")));
    }

    @Test
    void blankPatternsAreIgnored() {
        ExcludePatterns excludes = ExcludePatterns.of(List.of("", "  ", "/"));
        assertFalse(excludes.matches(Path.of("anything")));
    }
}
