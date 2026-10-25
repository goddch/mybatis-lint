package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.discovery.MapperFileScanner;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Severity;
import com.mybatislint.analyzer.xml.MapperSqlFragmentIndex;
import com.mybatislint.analyzer.xml.MapperResultMapIndex;
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
        private final int mapperFileCount;

        public Result(List<Diagnostic> diagnostics, int mapperFileCount) {
            this.diagnostics = diagnostics;
            this.mapperFileCount = mapperFileCount;
        }

        public List<Diagnostic> getDiagnostics() {
            return diagnostics;
        }

        /** Number of well-formed XML files with a {@code <mapper>} root element that were analyzed. */
        public int getMapperFileCount() {
            return mapperFileCount;
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.getSeverity() == Severity.ERROR);
        }
    }

    public Result run(Path workspaceRoot) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        JavaMapperIndex javaIndex = JavaMapperIndex.build(workspaceRoot);
        // typeAlias 検証(未解決候補の検出)は、Mapper インターフェースだけでなく
        // ワークスペース内の Java クラス/インターフェース全般の simple name を
        // 知る必要があるため、専用の索引を別途作る。
        JavaTypeIndex typeIndex = JavaTypeIndex.build(workspaceRoot);
        List<Path> mapperFiles = scanner.scan(workspaceRoot);
        // <include refid="namespace.sqlId"> は別ファイルの <sql id> を参照できる。
        // そのため、各ファイルを個別に解析する前にワークスペース内の SQL fragment を
        // 一度だけ索引化し、全ファイルの解析で共有する。
        MapperSqlFragmentIndex sqlFragmentIndex = MapperSqlFragmentIndex.build(mapperFiles);
        MapperResultMapIndex resultMapIndex = MapperResultMapIndex.build(mapperFiles);
        // scanner は pom.xml なども含むすべての XML を返すため、「mapper が 1 つも
        // 見つからなかった」と「問題がなかった」を利用者が区別できるよう、
        // ルートが <mapper> だったファイルの数を別に数えておく。
        int mapperFileCount = 0;
        for (Path file : mapperFiles) {
            Path relative = relativize(workspaceRoot, file);
            MapperXmlAnalyzer.FileResult fileResult = analyzer.analyzeFile(file, relative, file.toUri().toString(),
                    javaIndex, sqlFragmentIndex, typeIndex, resultMapIndex);
            diagnostics.addAll(fileResult.getDiagnostics());
            if (fileResult.isMapperFile()) {
                mapperFileCount++;
            }
        }
        return new Result(diagnostics, mapperFileCount);
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
