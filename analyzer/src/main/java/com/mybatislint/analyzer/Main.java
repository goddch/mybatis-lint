package com.mybatislint.analyzer;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.cli.DiagnosticFilter;
import com.mybatislint.analyzer.discovery.ExcludePatterns;
import com.mybatislint.analyzer.json.JsonWriter;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point.
 *
 * <p>Usage: {@code java -jar analyzer.jar [--exclude <glob>]... [--disable <rule>]... <workspace-directory>}
 *
 * <p>{@code --exclude} takes a glob relative to the workspace (see
 * {@link ExcludePatterns}) and may be given more than once. {@code --disable}
 * takes a rule ID, with or without the {@code mybatis-lint/} prefix, and may
 * also be repeated (see {@link DiagnosticFilter}).
 *
 * <p>Emits a single, deterministic JSON document to stdout (see
 * {@code docs/diagnostics-schema.md}) and exits with a non-zero status code
 * when any error-severity diagnostic was found. Stdout is reserved for the
 * JSON payload; any incidental logging goes to stderr.
 */
public final class Main {

    private static final String USAGE =
            "Usage: java -jar analyzer.jar [--exclude <glob>]... [--disable <rule>]... <workspace-directory>";

    private Main() {
    }

    public static void main(String[] args) {
        List<String> excludes = new ArrayList<>();
        List<String> disabledRules = new ArrayList<>();
        String workspace = null;
        for (int i = 0; i < args.length; i++) {
            if ("--exclude".equals(args[i]) && i + 1 < args.length) {
                excludes.add(args[++i]);
            } else if ("--disable".equals(args[i]) && i + 1 < args.length) {
                disabledRules.add(args[++i]);
            } else if (workspace == null && !args[i].startsWith("--")) {
                workspace = args[i];
            } else {
                workspace = null;
                break;
            }
        }
        if (workspace == null) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        // 綴りを誤ったルールは黙って無視されるので、気づけるよう stderr に出す。
        for (String unknown : DiagnosticFilter.unknownRuleIds(disabledRules)) {
            System.err.println("Unknown rule id in --disable: " + unknown);
        }

        Path workspaceRoot = Path.of(workspace);
        AnalyzerCli.Result result = new AnalyzerCli(ExcludePatterns.of(excludes), disabledRules).run(workspaceRoot);

        PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        stdout.print(JsonWriter.write(result.getDiagnostics(), result.getMapperFileCount()));
        stdout.flush();

        System.exit(result.hasErrors() ? 1 : 0);
    }
}
