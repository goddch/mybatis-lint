package com.mybatislint.analyzer.javaindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.mybatislint.analyzer.discovery.ExcludePatterns;
import com.mybatislint.analyzer.discovery.JavaFileScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * ワークスペースの Java ファイルを構文解析した結果。{@link JavaMapperIndex} と
 * {@link JavaTypeIndex} は同じファイルを読むので、1 回だけ解析して両方に渡す。
 *
 * <p>Java ファイルの解析はワークスペース全体の解析でいちばん時間がかかるので、
 * ファイルごとに並列で解析する。結果の順番はファイルを見つけた順のまま変えない。</p>
 */
public final class JavaSources {

    /** 構文解析できた Java ファイル。 */
    public record ParsedFile(Path file, CompilationUnit unit) {
    }

    // lombok.config の lombok.noArgsConstructor.extraPrivate = true があると、@Data / @Value の
    // クラスに private の引数なしコンストラクタが足される。どのクラスが対象か(lombok.config は
    // ディレクトリごとに効く)までは見ないで、どこかにあれば全体で「あるかもしれない」とする。
    private static final Pattern EXTRA_PRIVATE =
            Pattern.compile("(?m)^\\s*lombok\\.noArgsConstructor\\.extraPrivate\\s*=\\s*true\\b");

    private final List<ParsedFile> files;
    private final boolean lombokAddsNoArgsConstructor;

    private JavaSources(List<ParsedFile> files, boolean lombokAddsNoArgsConstructor) {
        this.files = files;
        this.lombokAddsNoArgsConstructor = lombokAddsNoArgsConstructor;
    }

    public static JavaSources empty() {
        return new JavaSources(List.of(), false);
    }

    public static JavaSources parse(Path workspaceRoot, ExcludePatterns excludes) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return empty();
        }
        JavaFileScanner scanner = new JavaFileScanner(excludes);
        List<ParsedFile> parsed = scanner.scan(workspaceRoot).parallelStream()
                .map(JavaSources::parseFile)
                .filter(Objects::nonNull)
                .toList();
        boolean extraPrivate = scanner.scanLombokConfigs(workspaceRoot).stream().anyMatch(JavaSources::enablesExtraPrivate);
        return new JavaSources(parsed, extraPrivate);
    }

    public List<ParsedFile> files() {
        return files;
    }

    /** Lombok が @Data / @Value のクラスに引数なしのコンストラクタを足すかもしれない。 */
    public boolean lombokAddsNoArgsConstructor() {
        return lombokAddsNoArgsConstructor;
    }

    private static boolean enablesExtraPrivate(Path config) {
        try {
            return EXTRA_PRIVATE.matcher(Files.readString(config, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            // 読めない設定は、書かれているかもしれないものとして扱う(警告しない側に倒す)。
            return true;
        }
    }

    private static ParsedFile parseFile(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            // JavaParser のデフォルト言語レベルは record 宣言(Java 14+)を解析できない。
            // このプロジェクトは Java 17 を前提にしているため、明示的に JAVA_17 を指定する。
            // 並列に解析するので、設定もパーサーもファイルごとに作る。
            ParserConfiguration configuration =
                    new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return null;
            }
            return new ParsedFile(file, result.getResult().get());
        } catch (IOException e) {
            // 1 ファイル読めないだけでワークスペース全体の索引作りを止めたくないため、
            // このファイルの分は諦めて次のファイルに進むフェイルオープン方針。
            return null;
        }
    }
}
