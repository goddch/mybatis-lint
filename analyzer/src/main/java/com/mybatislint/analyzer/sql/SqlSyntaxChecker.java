package com.mybatislint.analyzer.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * JSqlParser で SQL 1 文を読み、読めなかった位置を返す。
 *
 * <p>JSqlParser は 1 つの文法で Oracle / PostgreSQL / MySQL などの書き方をまとめて受け付ける。
 * そのため DB ごとの違いは、「JSqlParser が読めないが、その DB では正しい書き方」を
 * チェックの前に外すことで扱う({@link #isKnownUnsupported})。</p>
 */
public final class SqlSyntaxChecker implements AutoCloseable {

    // 複雑な SQL でパーサーが極端に遅くなることがあるので、打ち切る。打ち切ったものは報告しない。
    private static final long TIMEOUT_MILLIS = 3000;

    private static final Pattern PROCEDURAL = Pattern.compile("(?is)^\\s*(BEGIN|DECLARE|CALL|EXEC|EXECUTE)\\b.*");
    // Oracle の RETURNING ... INTO :out は JSqlParser が読めない。
    private static final Pattern RETURNING_INTO = Pattern.compile("(?is).*\\bRETURNING\\b.+\\bINTO\\b.*");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mybatis-lint-sql-parser");
        // 打ち切ったパーサーが残っていても、解析エンジンの終了を妨げないようにする。
        thread.setDaemon(true);
        return thread;
    });

    /** 読めなかった位置。{@code line} と {@code column} は SQL の中での 1 始まりの位置。 */
    public record SyntaxError(int line, int column, String near) {
        /** SQL の終わりで読めなくなった({@code WHERE} のあとが空、など)。 */
        public boolean atEnd() {
            return near.isEmpty();
        }
    }

    /** チェックの結果。{@code error} は SQL が読めたら null。{@code checked} は実際に読んだかどうか。 */
    public record Result(boolean checked, SyntaxError error) {
        static final Result SKIPPED = new Result(false, null);
        static final Result OK = new Result(true, null);
    }

    /** その DB では正しいが、JSqlParser では読めないと分かっている書き方か。 */
    public static boolean isKnownUnsupported(String sql, SqlDialect dialect) {
        if (PROCEDURAL.matcher(sql).matches() || sql.startsWith("{")) {
            return true;
        }
        return dialect == SqlDialect.ORACLE && RETURNING_INTO.matcher(sql).matches();
    }

    public Result check(String sql, SqlDialect dialect) {
        if (sql.isBlank() || isKnownUnsupported(sql, dialect)) {
            return Result.SKIPPED;
        }
        try {
            CCJSqlParserUtil.parse(sql, executor, parser -> parser.withTimeOut(TIMEOUT_MILLIS));
            return Result.OK;
        } catch (JSQLParserException e) {
            SyntaxError error = toSyntaxError(e);
            // 打ち切りやパーサー内部の失敗は SQL の誤りとは限らないので、報告しない。
            return error == null ? Result.SKIPPED : new Result(true, error);
        } catch (RuntimeException e) {
            return Result.SKIPPED;
        }
    }

    private static SyntaxError toSyntaxError(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ParseException parseException && parseException.currentToken != null) {
                Token token = parseException.currentToken.next != null
                        ? parseException.currentToken.next
                        : parseException.currentToken;
                String near = token.image == null ? "" : token.image;
                return new SyntaxError(token.beginLine, token.beginColumn, near);
            }
        }
        // 字句の段階のエラー(TokenMgrException)などは位置が取れず、全角記号のように
        // DB によっては通るものもあるので報告しない。
        return null;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
