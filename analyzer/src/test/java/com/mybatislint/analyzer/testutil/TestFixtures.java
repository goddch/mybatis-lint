package com.mybatislint.analyzer.testutil;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the repository's shared {@code fixtures/} directory from a test's working directory. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Path fixturesRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path fixtures = candidate.resolve("fixtures");
            if (Files.isDirectory(fixtures)) {
                return fixtures;
            }
            candidate = candidate.getParent();
            if (candidate == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate the 'fixtures' directory from " + Path.of("").toAbsolutePath());
    }
}
