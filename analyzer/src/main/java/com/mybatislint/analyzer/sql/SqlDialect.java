package com.mybatislint.analyzer.sql;

import java.util.Locale;

/** SQL の構文チェックで対応している DB。 */
public enum SqlDialect {
    ORACLE("oracle", "Oracle"),
    POSTGRESQL("postgresql", "PostgreSQL"),
    MYSQL("mysql", "MySQL");

    private final String id;
    private final String displayName;

    SqlDialect(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** 設定や {@code --sql-dialect} で使う名前。 */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 設定値や statement の {@code databaseId} から DB を決める。わからない名前は null。
     * {@code databaseId} は利用者が databaseIdProvider で付ける任意の名前だが、
     * DB の製品名をそのまま(小文字で)使うことが多いので、よくある書き方だけ受け付ける。
     */
    public static SqlDialect fromName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "oracle" -> ORACLE;
            case "postgresql", "postgres", "pgsql" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            default -> null;
        };
    }
}
