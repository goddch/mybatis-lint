package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.discovery.MapperFileScanner;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Severity;
import com.mybatislint.analyzer.xml.MapperXmlAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates discovery + analysis of every mapper XML file under a
 * workspace root. This is the reusable core invoked by {@code Main} (and,
 * indirectly, by the VS Code extension via the packaged CLI jar).
 */
public final class AnalyzerCli {

    private final MapperFileScanner scanner;
    private final MapperXmlAnalyzer analyzer;

    public AnalyzerCli() {
        this(new MapperFileScanner(), new MapperXmlAnalyzer());
    }

    public AnalyzerCli(MapperFileScanner scanner, MapperXmlAnalyzer analyzer) {
        this.scanner = scanner;
        this.analyzer = analyzer;
    }

    /** Result of running the analyzer against a workspace root. */
    public static final class Result {
        private final List<Diagnostic> diagnostics;

        public Result(List<Diagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        public List<Diagnostic> getDiagnostics() {
            return diagnostics;
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.getSeverity() == Severity.ERROR);
        }
    }

    public Result run(Path workspaceRoot) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        JavaMapperIndex javaIndex = JavaMapperIndex.build(workspaceRoot);
        for (Path file : scanner.scan(workspaceRoot)) {
            Path relative = relativize(workspaceRoot, file);
            diagnostics.addAll(analyzer.analyze(file, relative, file.toUri().toString(), javaIndex));
        }
        return new Result(diagnostics);
    }

    private static Path relativize(Path workspaceRoot, Path file) {
        try {
            return workspaceRoot.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return file;
        }
    }
}
