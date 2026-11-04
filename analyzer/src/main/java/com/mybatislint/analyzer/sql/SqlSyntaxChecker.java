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

    // Oracle では正しいが JSqlParser が読めない書き方。読めなかったときだけ見るので、たまたま
    // 文字列の中などに一致しても、その statement のチェックを諦めるだけで誤検知にはならない。
    private static final Pattern ORACLE_UNSUPPORTED = Pattern.compile("(?is)"
            + "\\b(SUB)?PARTITION\\s*(\\(|FOR\\b)"            // FROM T PARTITION (P1) A
            + "|[^(\\s]\\s+PARTITION\\s+BY\\b"                // パーティション外部結合(OVER (PARTITION BY は読める)
            + "|\\bAS\\s+OF\\s+(TIMESTAMP|SCN|PERIOD)\\b"       // フラッシュバック問い合わせ
            + "|\\bVERSIONS\\s+(BETWEEN|PERIOD)\\b"
            + "|\\bSAMPLE(\\s+BLOCK)?\\s*\\("
            + "|\\bMODEL\\s+(DIMENSION|PARTITION|MAIN|UNIQUE|IGNORE|KEEP|RETURN|REFERENCE)\\b"
            + "|\\bMATCH_RECOGNIZE\\s*\\("
            + "|^\\s*WITH\\s+(FUNCTION|PROCEDURE)\\b"
            + "|\\bTREAT\\s*\\("
            + "|\\bNEW\\s+[\\w.$#]+\\s*\\("                   // オブジェクト型のコンストラクタ
            + "|\\bIS\\s+(NOT\\s+)?(NAN|INFINITE|OF|A\\s+SET|EMPTY|PRESENT)\\b"
            + "|\\bLIKE[C24]\\b"
            + "|^\\s*(INSERT\\s+(/\\*.*?\\*/\\s*)?INTO|(UPDATE|DELETE)\\s+(/\\*.*?\\*/\\s*)?(FROM\\s+)?)\\s*\\("  // 副問い合わせへの DML
            + "|\\bLOG\\s+ERRORS\\b"
            + "|\\bCURRENT\\s+OF\\b");

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
        Result result = parse(sql);
        if (result.error() == null) {
            return result;
        }
        // JSqlParser は FINAL、OUTPUT、STATEMENT、INT、MAP など、Oracle では列名や別名に使える語の一部を
        // 予約語として扱い、A.FINAL や INT.ID の「.」で読めなくなる。どの語が当たるかは版によって変わるので、
        // 「.」でつないだ名前をすべて引用符で囲んで読み直し、読めたら名前のせいだったとして報告しない。
        String quoted = quoteDottedNames(sql);
        if (!quoted.equals(sql)) {
            Result retry = parse(quoted);
            if (retry.checked() && retry.error() == null) {
                return Result.OK;
            }
        }
        if (dialect == SqlDialect.ORACLE && ORACLE_UNSUPPORTED.matcher(sql).find()) {
            return Result.SKIPPED;
        }
        return result;
    }

    private Result parse(String sql) {
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

    /**
     * {@code A.FINAL} を {@code "A"."FINAL"} のように、「.」でつないだ名前を引用符で囲む。
     * 文字列、引用符で囲んだ名前、コメントの中は変えない。
     */
    static String quoteDottedNames(String sql) {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            int skipTo = skipQuotedOrComment(sql, i);
            if (skipTo > i) {
                out.append(sql, i, skipTo);
                i = skipTo;
            } else if (isNameStart(c) && (i == 0 || !isNamePart(sql.charAt(i - 1)))) {
                int end = nameEnd(sql, i);
                String name = sql.substring(i, end);
                int next = skipSpaces(sql, end);
                boolean before = next < sql.length() && sql.charAt(next) == '.';
                boolean after = precededByDot(out);
                out.append(before || after ? '"' + name + '"' : name);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 文字列('...')、引用符で囲んだ名前("...")、コメントなら、その終わりの位置。そうでなければ {@code i}。 */
    private static int skipQuotedOrComment(String sql, int i) {
        char c = sql.charAt(i);
        if (c == '\'' || c == '"') {
            int close = sql.indexOf(c, i + 1);
            return close < 0 ? sql.length() : close + 1;
        }
        if (sql.startsWith("--", i)) {
            int close = sql.indexOf('\n', i);
            return close < 0 ? sql.length() : close;
        }
        if (sql.startsWith("/*", i)) {
            int close = sql.indexOf("*/", i + 2);
            return close < 0 ? sql.length() : close + 2;
        }
        return i;
    }

    private static boolean precededByDot(StringBuilder out) {
        int j = out.length() - 1;
        while (j >= 0 && Character.isWhitespace(out.charAt(j))) {
            j--;
        }
        return j >= 0 && out.charAt(j) == '.';
    }

    private static int nameEnd(String sql, int start) {
        int end = start;
        while (end < sql.length() && isNamePart(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int skipSpaces(String sql, int start) {
        int i = start;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    // Oracle の名前には $ と # も使える(SERIAL# など)。
    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
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
