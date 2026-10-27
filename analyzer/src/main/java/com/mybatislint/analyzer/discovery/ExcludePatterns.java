package com.mybatislint.analyzer.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析から外すパスの glob パターン({@code --exclude} で指定する)。
 *
 * <p>パターンはワークスペースからの相対パスで、区切りは {@code /}。
 * {@code *} は 1 階層分、{@code **} は何階層でも一致する。{@code /} を含まない
 * パターン(例: {@code docker-data})は、どの階層にあるディレクトリ名・ファイル名にも
 * 一致する。ディレクトリに一致すれば、その中はすべて対象外になるので、
 * {@code docker/} や {@code docker/**} と書いても {@code docker} と同じ意味になる。</p>
 */
public final class ExcludePatterns {

    private static final ExcludePatterns NONE = new ExcludePatterns(List.of(), List.of());

    private final List<Pattern> pathPatterns;
    private final List<Pattern> namePatterns;

    private ExcludePatterns(List<Pattern> pathPatterns, List<Pattern> namePatterns) {
        this.pathPatterns = pathPatterns;
        this.namePatterns = namePatterns;
    }

    public static ExcludePatterns none() {
        return NONE;
    }

    public static ExcludePatterns of(List<String> globs) {
        List<Pattern> pathPatterns = new ArrayList<>();
        List<Pattern> namePatterns = new ArrayList<>();
        for (String glob : globs) {
            String normalized = normalize(glob);
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.contains("/")) {
                pathPatterns.add(toRegex(normalized));
            } else {
                namePatterns.add(toRegex(normalized));
            }
        }
        return new ExcludePatterns(pathPatterns, namePatterns);
    }

    /** ワークスペースからの相対パスが、どれかのパターンに一致するか。 */
    public boolean matches(Path relativePath) {
        if (pathPatterns.isEmpty() && namePatterns.isEmpty()) {
            return false;
        }
        String path = relativePath.toString().replace('\\', '/');
        for (Pattern pattern : pathPatterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        Path fileName = relativePath.getFileName();
        String name = fileName == null ? path : fileName.toString();
        for (Pattern pattern : namePatterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String glob) {
        String value = glob == null ? "" : glob.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        // ディレクトリに一致すれば中身も飛ばすので、末尾の "/**" や "/" は不要。
        while (value.endsWith("/**") || value.endsWith("/")) {
            value = value.endsWith("/") ? value.substring(0, value.length() - 1)
                    : value.substring(0, value.length() - 3);
        }
        return value;
    }

    private static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    // "**/" は 0 階層以上を表すので、"**/mapper" は "mapper" にも一致させる。
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
