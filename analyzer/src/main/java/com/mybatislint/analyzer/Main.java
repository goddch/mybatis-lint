package com.mybatislint.analyzer;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.cli.DiagnosticFilter;
import com.mybatislint.analyzer.discovery.ExcludePatterns;
import com.mybatislint.analyzer.json.JsonWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point.
 *
 * <p>Usage: {@code java -jar analyzer.jar [--exclude <glob>]... [--disable <rule>]... [--sql-dialect <db>] <workspace-directory>}
 *
 * <p>{@code --exclude} takes a glob relative to the workspace (see
 * {@link ExcludePatterns}) and may be given more than once. {@code --disable}
 * takes a rule ID, with or without the {@code mybatis-lint/} prefix, and may
 * also be repeated (see {@link DiagnosticFilter}). {@code --sql-dialect} picks
 * the database whose grammar the SQL syntax check uses: {@code auto} (the
 * default, guessed from the project's settings and build files), {@code off},
 * {@code oracle}, {@code postgresql} or {@code mysql}.
 *
 * <p>Emits a single, deterministic JSON document to stdout (see
 * {@code docs/diagnostics-schema.md}) and exits with a non-zero status code
 * when any error-severity diagnostic was found. Stdout is reserved for the
 * JSON payload; any incidental logging goes to stderr.
 *
 * <p>{@code java -jar analyzer.jar --server} keeps running instead and answers
 * one request per stdin line (see {@link #serve()}).
 */
public final class Main {

    private static final String USAGE =
            "Usage: java -jar analyzer.jar [--exclude <glob>]... [--disable <rule>]... [--sql-dialect <db>] <workspace-directory>\n"
                    + "       java -jar analyzer.jar --server";

    private Main() {
    }

    /** 1 回の解析の指定。引数が正しくなければ {@link #parse} は null を返す。 */
    private record Request(List<String> excludes, List<String> disabledRules, String sqlDialect, String workspace) {

        static Request parse(String[] args) {
            List<String> excludes = new ArrayList<>();
            List<String> disabledRules = new ArrayList<>();
            String sqlDialect = "auto";
            String workspace = null;
            for (int i = 0; i < args.length; i++) {
                if ("--exclude".equals(args[i]) && i + 1 < args.length) {
                    excludes.add(args[++i]);
                } else if ("--disable".equals(args[i]) && i + 1 < args.length) {
                    disabledRules.add(args[++i]);
                } else if ("--sql-dialect".equals(args[i]) && i + 1 < args.length) {
                    sqlDialect = args[++i];
                } else if (workspace == null && !args[i].startsWith("--")) {
                    workspace = args[i];
                } else {
                    return null;
                }
            }
            return workspace == null ? null : new Request(excludes, disabledRules, sqlDialect, workspace);
        }

        AnalyzerCli.Result run(AnalyzerCli.Caches caches) {
            // 綴りを誤ったルールは黙って無視されるので、気づけるよう stderr に出す。
            for (String unknown : DiagnosticFilter.unknownRuleIds(disabledRules)) {
                System.err.println("Unknown rule id in --disable: " + unknown);
            }
            return new AnalyzerCli(ExcludePatterns.of(excludes), disabledRules)
                    .withSqlDialect(sqlDialect)
                    .run(Path.of(workspace), caches);
        }
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--server".equals(args[0])) {
            serve();
            return;
        }
        Request request = Request.parse(args);
        if (request == null) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        AnalyzerCli.Result result = request.run(null);

        PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        stdout.print(JsonWriter.write(result.getDiagnostics(), result.getMapperFileCount()));
        stdout.flush();

        System.exit(result.hasErrors() ? 1 : 0);
    }

    /**
     * 解析を 1 回ずつ起動し直さずに、stdin から 1 行ずつ指定を受け取って解析し続ける。
     *
     * <p>保存のたびに JVM を起動して全ファイルを読み直すと時間がかかるので、VS Code 拡張は
     * このモードで 1 つのプロセスを使い続ける。JIT が効いたままになり、更新日時と大きさが
     * 変わっていないファイルは前に読んだ結果を使う。</p>
     *
     * <p>1 行は CLI と同じ引数をタブでつないだもの。応答は {@code Content-Length: <バイト数>} の行に
     * 続けて、CLI と同じ JSON をその長さだけ書く。指定が正しくなければ長さ 0 の応答を返し、
     * 理由を stderr に書く。stdin が閉じられるか空行を受け取ったら終わる。</p>
     */
    private static void serve() {
        AnalyzerCli.Caches caches = new AnalyzerCli.Caches();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        OutputStream stdout = System.out;
        try {
            String line;
            while ((line = stdin.readLine()) != null && !line.isEmpty()) {
                Request request = Request.parse(line.split("\t", -1));
                byte[] body = new byte[0];
                if (request == null) {
                    System.err.println("Invalid request: " + line);
                } else {
                    try {
                        AnalyzerCli.Result result = request.run(caches);
                        body = JsonWriter.write(result.getDiagnostics(), result.getMapperFileCount())
                                .getBytes(StandardCharsets.UTF_8);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        // 1 回の失敗でプロセスを終わらせない。拡張は長さ 0 の応答を失敗として扱う。
                        System.err.println("Analysis failed: " + e);
                    }
                }
                stdout.write(("Content-Length: " + body.length + "\n").getBytes(StandardCharsets.US_ASCII));
                stdout.write(body);
                stdout.flush();
            }
        } catch (IOException e) {
            System.err.println("Server stopped: " + e.getMessage());
        }
    }
}
