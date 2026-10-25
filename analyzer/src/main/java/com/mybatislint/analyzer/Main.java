package com.mybatislint.analyzer;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.json.JsonWriter;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * CLI entry point.
 *
 * <p>Usage: {@code java -jar analyzer.jar <workspace-directory>}
 *
 * <p>Emits a single, deterministic JSON document to stdout (see
 * {@code docs/diagnostics-schema.md}) and exits with a non-zero status code
 * when any error-severity diagnostic was found. Stdout is reserved for the
 * JSON payload; any incidental logging goes to stderr.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar analyzer.jar <workspace-directory>");
            System.exit(2);
            return;
        }

        Path workspaceRoot = Path.of(args[0]);
        AnalyzerCli.Result result = new AnalyzerCli().run(workspaceRoot);

        PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        stdout.print(JsonWriter.write(result.getDiagnostics(), result.getMapperFileCount()));
        stdout.flush();

        System.exit(result.hasErrors() ? 1 : 0);
    }
}
