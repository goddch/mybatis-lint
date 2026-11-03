package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import com.mybatislint.analyzer.sql.SqlDialect;
import com.mybatislint.analyzer.sql.SqlSyntaxChecker;
import com.mybatislint.analyzer.xml.DynamicSqlRenderer.Rendered;
import com.mybatislint.analyzer.xml.DynamicSqlRenderer.Variant;
import com.mybatislint.analyzer.xml.MapperXmlTree.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code mybatis-lint/sql-syntax-error}: statement の SQL を、その DB の文法として読めるか確かめる。
 *
 * <h2>どの DB として読むか</h2>
 * <p>statement に {@code databaseId} があり、知っている DB 名ならその DB。なければワークスペース全体の DB
 * (設定で指定されたもの、または設定ファイルから推定したもの)。どちらも決まらない statement は読まない。
 * 違う DB の文法で読むと、正しい SQL まで誤りになるため。</p>
 *
 * <h2>動的 SQL</h2>
 * <p>SQL プレビューと同じ規則で組み立てる({@link DynamicSqlRenderer})。まず {@code <if>} をすべて
 * 成り立たせて読み、読めなければ、別の分岐の選び方でも同じ XML の位置で読めないことを確かめてから報告する
 * ({@link #confirmed})。一部の組み合わせでだけ壊れるもの(同時には成り立たない 2 つの {@code <if>} など)は、
 * 書き間違いとは限らないので報告しない。分岐の組み立てミスは {@code dangling-sql-joiner} などが扱う。</p>
 *
 * <h2>読まないもの</h2>
 * <ul>
 *   <li>{@code ${...}} が残る statement(値しだいで SQL が変わる)</li>
 *   <li>見つからない {@code <include>} がある statement、{@code statementType="CALLABLE"}</li>
 *   <li>PL/SQL ブロックや Oracle の {@code RETURNING ... INTO} など、パーサーが読めないと分かっている書き方</li>
 *   <li>途中に {@code ;} があり、複数の SQL をつないでいるもの</li>
 * </ul>
 * <p>パーサーが読めないことは SQL の誤りとは限らない(パーサーが知らない書き方かもしれない)ので、warning にとどめる。</p>
 */
public final class MapperSqlSyntaxValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private final List<Path> mapperFiles;
    private final SqlDialect projectDialect;

    /** どれだけの statement を読めたか。拡張機能のログで、チェックの範囲を伝えるために使う。 */
    public record Stats(int checked, int skipped) {
    }

    public record Output(List<Diagnostic> diagnostics, Stats stats) {
    }

    /** @param projectDialect ワークスペース全体の DB。決まらないときは null */
    public MapperSqlSyntaxValidator(List<Path> mapperFiles, SqlDialect projectDialect) {
        this.mapperFiles = mapperFiles;
        this.projectDialect = projectDialect;
    }

    public Output validate(Function<Path, Path> displayPath) {
        Map<Path, String> sources = new LinkedHashMap<>();
        Map<Path, Element> mappers = new LinkedHashMap<>();
        Map<String, Element> fragments = new HashMap<>();
        for (Path file : mapperFiles) {
            try {
                String xml = XmlSources.readString(file);
                Element root = MapperXmlTree.parse(xml);
                if (root == null || !root.name().equals("mapper")) {
                    continue;
                }
                sources.put(file, xml);
                mappers.put(file, root);
                String namespace = root.attribute("namespace").trim();
                for (Element child : root.childElements()) {
                    if (child.name().equals("sql")) {
                        // 同じ id が複数あるときは最初のものにする(重複は別のルールが報告する)。
                        fragments.putIfAbsent(namespace + "." + child.attribute("id").trim(), child);
                    }
                }
            } catch (Exception e) {
                // 読めないファイルや壊れた XML は MapperXmlAnalyzer が報告する。
            }
        }
        DynamicSqlRenderer renderer = new DynamicSqlRenderer((namespace, id) -> fragments.get(namespace + "." + id));

        List<Diagnostic> diagnostics = new ArrayList<>();
        int checked = 0;
        int skipped = 0;
        try (SqlSyntaxChecker checker = new SqlSyntaxChecker()) {
            for (Map.Entry<Path, Element> entry : mappers.entrySet()) {
                Path file = entry.getKey();
                String xml = sources.get(file);
                Element mapper = entry.getValue();
                String namespace = mapper.attribute("namespace").trim();
                for (Element statement : mapper.childElements()) {
                    if (!STATEMENT_TAGS.contains(statement.name())) {
                        continue;
                    }
                    SqlDialect dialect = dialectFor(statement);
                    if (dialect == null || "CALLABLE".equalsIgnoreCase(statement.attribute("statementType").trim())) {
                        skipped++;
                        continue;
                    }
                    Attempt allOn = attempt(renderer, checker, statement, namespace, Variant.ALL_ON, dialect);
                    if (allOn == null) {
                        skipped++;
                        continue;
                    }
                    checked++;
                    if (allOn.error == null) {
                        continue;
                    }
                    if (confirmed(renderer, checker, statement, namespace, dialect, allOn)) {
                        diagnostics.add(
                                toDiagnostic(displayPath.apply(file), file.toUri().toString(), xml, allOn, dialect));
                    }
                }
            }
        }
        return new Output(diagnostics, new Stats(checked, skipped));
    }

    /**
     * {@code <if>} をすべて成り立たせて読めなかった誤りが、分岐の組み合わせのせいではないかを確かめる。
     *
     * <ul>
     *   <li>{@code <if>} の外の誤り: {@code <if>} をすべて成り立たせない形でも、同じ位置で読めなければ報告する</li>
     *   <li>{@code <if>} の中の誤り: その {@code <if>}(と、それを囲む {@code <if>})だけを成り立たせた形でも、
     *       同じ位置で読めなければ報告する。同時には成り立たない 2 つの {@code <if>}
     *       ({@code ORDER BY} を 2 つ書き分けるなど)は、片方だけにすると読めるので報告しない</li>
     * </ul>
     */
    private static boolean confirmed(DynamicSqlRenderer renderer, SqlSyntaxChecker checker, Element statement,
                                     String namespace, SqlDialect dialect, Attempt allOn) {
        int errorStart = allOn.errorStart();
        Set<Element> enclosing = Collections.newSetFromMap(new IdentityHashMap<>());
        collectEnclosingIfs(statement, errorStart, enclosing);
        Variant variant = enclosing.isEmpty() ? Variant.ALL_OFF : Variant.only(enclosing);
        Attempt again = attempt(renderer, checker, statement, namespace, variant, dialect);
        return again != null && again.error != null && again.errorStart() == errorStart;
    }

    private static void collectEnclosingIfs(Element element, int offset, Set<Element> target) {
        for (Element child : element.childElements()) {
            if (!child.contains(offset)) {
                continue;
            }
            if (child.name().equals("if")) {
                target.add(child);
            }
            collectEnclosingIfs(child, offset, target);
        }
    }

    /** {@code databaseId} があればその DB。知らない名前なら null(その statement は読まない)。 */
    private SqlDialect dialectFor(Element statement) {
        String databaseId = statement.attribute("databaseId").trim();
        return databaseId.isEmpty() ? projectDialect : SqlDialect.fromName(databaseId);
    }

    /** 1 通りの組み立てと、その読み取り結果。 */
    private record Attempt(Rendered rendered, String sql, SqlSyntaxChecker.SyntaxError error) {
        /** 読めなかった位置の、XML での位置。 */
        int errorStart() {
            if (error.atEnd()) {
                return rendered.xmlOffset(sql.length() - 1);
            }
            return rendered.xmlOffset(sqlIndex(sql, error.line(), error.column()));
        }
    }

    /** 組み立てて読む。読まない SQL(${...} がある、など)なら null。 */
    private static Attempt attempt(DynamicSqlRenderer renderer, SqlSyntaxChecker checker, Element statement,
                                   String namespace, Variant variant, SqlDialect dialect) {
        Rendered rendered = renderer.render(statement, namespace, variant);
        if (rendered.hasDollar() || !rendered.complete()) {
            return null;
        }
        String sql = withoutTrailingSemicolon(rendered.sql());
        if (sql.isEmpty() || hasSemicolonOutsideLiterals(sql)) {
            return null;
        }
        SqlSyntaxChecker.Result result = checker.check(sql, dialect);
        return result.checked() ? new Attempt(rendered, sql, result.error()) : null;
    }

    private static String withoutTrailingSemicolon(String sql) {
        String trimmed = sql.strip();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).strip() : trimmed;
    }

    private static boolean hasSemicolonOutsideLiterals(String sql) {
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ';') {
                return true;
            }
        }
        return false;
    }

    private static Diagnostic toDiagnostic(Path displayFile, String uri, String xml, Attempt attempt,
                                           SqlDialect dialect) {
        String sql = attempt.sql;
        Rendered rendered = attempt.rendered;
        SqlSyntaxChecker.SyntaxError error = attempt.error;
        int start;
        int end;
        String message;
        if (error.atEnd()) {
            // SQL の終わりで読めなくなった(WHERE のあとが空、など)ときは、最後の語を指す。
            int last = sql.length() - 1;
            int wordStart = last;
            while (wordStart > 0 && !Character.isWhitespace(sql.charAt(wordStart - 1))) {
                wordStart--;
            }
            start = rendered.xmlOffset(wordStart);
            end = rendered.xmlOffset(last) + 1;
            message = "SQL ends unexpectedly (checked as " + dialect.displayName() + " SQL).";
        } else {
            int index = sqlIndex(sql, error.line(), error.column());
            int last = Math.min(sql.length(), index + error.near().length()) - 1;
            start = rendered.xmlOffset(index);
            end = last >= index ? rendered.xmlOffset(last) + 1 : start;
            message = "SQL syntax error near '" + error.near() + "' (checked as " + dialect.displayName() + " SQL).";
        }
        if (end < start) {
            // <include> の先のように、SQL の並びと XML の並びが違うときは開始位置だけを指す。
            end = start;
        }
        return new Diagnostic(displayFile, uri, XmlPositions.range(xml, start, end), Severity.WARNING,
                RuleIds.SQL_SYNTAX_ERROR, message);
    }

    /** SQL の中の 1 始まりの行・列を、文字の位置にする。 */
    private static int sqlIndex(String sql, int line, int column) {
        int index = 0;
        for (int current = 1; current < line && index < sql.length(); index++) {
            if (sql.charAt(index) == '\n') {
                current++;
            }
        }
        return Math.max(0, Math.min(sql.length() - 1, index + column - 1));
    }
}
