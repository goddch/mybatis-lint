package com.mybatislint.analyzer.sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ワークスペースの設定ファイルとビルドファイルから、接続先の DB を推定する。
 *
 * <p>JDBC の URL({@code jdbc:oracle:} など)やドライバのクラス名、依存ライブラリの名前を探す。
 * 見つかった DB が 1 つだけのときだけ、その DB とみなす。複数見つかったとき(本番は Oracle で
 * 一部のモジュールだけ PostgreSQL、など)は、どの SQL がどちら向けかわからないので推定しない。
 * 違う DB の文法でチェックすると正しい SQL まで誤りになるため、迷ったらチェックしない側に倒す。</p>
 *
 * <p>H2 や HSQLDB はテスト用に入っていることが多いので、手がかりにしない。</p>
 */
public final class SqlDialectDetector {

    // 大きなファイル(データの XML など)を丸ごと読まないよう、先頭だけ見る。設定はたいてい先頭近くにある。
    private static final int MAX_CHARS = 256 * 1024;

    private static final Map<SqlDialect, List<String>> MARKERS = Map.of(
            SqlDialect.ORACLE, List.of("jdbc:oracle:", "oracle.jdbc.", "com.oracle.database.jdbc", "ojdbc"),
            SqlDialect.POSTGRESQL, List.of("jdbc:postgresql:", "org.postgresql"),
            SqlDialect.MYSQL, List.of("jdbc:mysql:", "com.mysql", "mysql-connector"));

    private SqlDialectDetector() {
    }

    /** 推定の結果。DB が決まらなかったときは {@code dialect} が null で、{@code reason} に理由が入る。 */
    public record Detection(SqlDialect dialect, String reason) {
    }

    /**
     * @param files 調べるファイル(設定ファイル、ビルドファイル、XML)
     * @param display ログに出すためのパスの表し方(ワークスペースからの相対パスなど)
     */
    public static Detection detect(List<Path> files, java.util.function.Function<Path, Path> display) {
        Map<SqlDialect, Path> evidence = new EnumMap<>(SqlDialect.class);
        for (Path file : files) {
            String text = readHead(file);
            if (text == null) {
                continue;
            }
            for (Map.Entry<SqlDialect, List<String>> entry : MARKERS.entrySet()) {
                if (evidence.containsKey(entry.getKey())) {
                    continue;
                }
                for (String marker : entry.getValue()) {
                    if (text.contains(marker)) {
                        evidence.put(entry.getKey(), file);
                        break;
                    }
                }
            }
        }
        if (evidence.size() == 1) {
            Map.Entry<SqlDialect, Path> only = evidence.entrySet().iterator().next();
            return new Detection(only.getKey(), "detected from " + display.apply(only.getValue()));
        }
        if (evidence.isEmpty()) {
            return new Detection(null, "no JDBC URL, driver, or driver dependency was found");
        }
        List<String> found = new ArrayList<>();
        evidence.forEach((dialect, file) -> found.add(dialect.displayName() + " (" + display.apply(file) + ")"));
        return new Detection(null, "more than one database was found: " + String.join(", ", found));
    }

    private static String readHead(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return (text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException e) {
            // UTF-8 でない設定ファイルなどは手がかりにしない。
            return null;
        }
    }
}
