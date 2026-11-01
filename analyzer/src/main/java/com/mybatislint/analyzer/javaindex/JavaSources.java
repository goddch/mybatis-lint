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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 解析をまたいで構文解析の結果を覚えておく場所({@code --server} で使う)。更新日時と大きさが
     * 前と同じファイルは解析し直さない。並列に解析するので、スレッドから同時に使える。
     */
    public static final class Cache {
        private final Map<Path, Cached> files = new ConcurrentHashMap<>();

        /** 構文解析できなかったファイルも、解析し直さないよう parsed を null にして覚える。 */
        private record Cached(FileTime modified, long size, ParsedFile parsed) {
        }

        private ParsedFile parse(Path file) {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (IOException e) {
                files.remove(file);
                return parseFile(file);
            }
            Cached cached = files.get(file);
            if (cached != null && cached.modified().equals(attributes.lastModifiedTime())
                    && cached.size() == attributes.size()) {
                return cached.parsed();
            }
            ParsedFile parsed = parseFile(file);
            files.put(file, new Cached(attributes.lastModifiedTime(), attributes.size(), parsed));
            return parsed;
        }

        /** 消えたファイルの分を忘れる。 */
        private void retainOnly(List<Path> current) {
            files.keySet().retainAll(new HashSet<>(current));
        }
    }

    public static JavaSources parse(Path workspaceRoot, ExcludePatterns excludes) {
        return parse(workspaceRoot, excludes, null);
    }

    /** {@code cache} があれば、変わっていないファイルはその結果を使う。 */
    public static JavaSources parse(Path workspaceRoot, ExcludePatterns excludes, Cache cache) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return empty();
        }
        JavaFileScanner scanner = new JavaFileScanner(excludes);
        List<Path> javaFiles = scanner.scan(workspaceRoot);
        if (cache != null) {
            cache.retainOnly(javaFiles);
        }
        List<ParsedFile> parsed = javaFiles.parallelStream()
                .map(file -> cache == null ? parseFile(file) : cache.parse(file))
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
