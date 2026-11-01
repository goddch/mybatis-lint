package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import com.mybatislint.analyzer.xml.ParameterTypeLookup.StatementContext;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 値を書く場所で {@code #{...}} ではなく {@code ${...}} を使っているものを見つける
 * ({@code mybatis-lint/unsafe-dollar-substitution})。
 *
 * <p>{@code ${...}} は値を SQL の文字列としてそのまま埋め込む。利用者が入力した値が入ると、
 * SQL そのものを書き換えられる(SQL インジェクション)。{@code #{...}} はバインド変数になるので安全。</p>
 *
 * <p>{@code ORDER BY ${column}} やテーブル名のように、{@code #{...}} では書けない場所もある。
 * そのため、値を書く場所だとわかるものだけを報告する。</p>
 * <ul>
 *   <li>比較演算子({@code =}、{@code <>}、{@code <} など)や {@code LIKE} のすぐあと</li>
 *   <li>{@code 'abc${name}'} のような文字列リテラルの中</li>
 *   <li>{@code IN (${ids})} の括弧のすぐ中</li>
 * </ul>
 *
 * <h2>判断できない場合は何もしない</h2>
 * <ul>
 *   <li>値の型が数値や boolean だとわかる(SQL を書き換える文字列にならない)</li>
 *   <li>{@code ${@com.example.Const@VALUE}} のような定数の参照</li>
 *   <li>SQL のコメントの中</li>
 * </ul>
 */
public final class MapperDollarSubstitutionValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete", "sql");
    private static final Set<String> SAFE_TYPES;

    static {
        Set<String> safe = new HashSet<>(ParameterTypeLookup.NUMERIC_TYPES);
        safe.addAll(Set.of("boolean", "Boolean"));
        SAFE_TYPES = Set.copyOf(safe);
    }

    private static final Pattern OPERATOR_BEFORE =
            Pattern.compile("(?i)(<>|!=|<=|>=|=|<|>|&lt;&gt;|&lt;=|&gt;=|&lt;|&gt;|(?<![A-Za-z0-9_$#])LIKE)\\s*$");
    private static final Pattern IN_LIST_BEFORE = Pattern.compile("(?i)(?<![A-Za-z0-9_$#])IN\\s*\\(\\s*$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)?");

    private final JavaMapperIndex javaIndex;
    private final ParameterTypeLookup types;

    public MapperDollarSubstitutionValidator(JavaMapperIndex javaIndex, JavaTypeIndex typeIndex) {
        this.javaIndex = javaIndex == null ? JavaMapperIndex.empty() : javaIndex;
        this.types = new ParameterTypeLookup(typeIndex == null ? JavaTypeIndex.empty() : typeIndex);
    }

    /** 値を書く場所の種類。メッセージの直し方が変わる。 */
    private enum Place { VALUE, LITERAL, IN_LIST }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            MapperInterfaceInfo mapperInterface = javaIndex.findInterface(mapper.getAttribute("namespace"));
            List<Diagnostic> diagnostics = new ArrayList<>();
            int cursor = 0;
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element statement) || !STATEMENT_TAGS.contains(statement.getTagName())) {
                    continue;
                }
                Matcher start = Pattern.compile("<" + statement.getTagName() + "(?=[\\s>/])").matcher(xml);
                if (!start.find(cursor)) {
                    break;
                }
                int tagEnd = XmlPositions.findStartTagEnd(xml, start.start());
                int end = xml.indexOf("</" + statement.getTagName() + ">", Math.max(tagEnd, start.start()));
                if (tagEnd < 0 || end < 0) {
                    break;
                }
                cursor = end;
                validateStatement(xml, tagEnd + 1, end, StatementContext.of(statement, mapperInterface),
                        displayFile, uri, diagnostics);
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    private void validateStatement(String xml, int bodyStart, int bodyEnd, StatementContext context,
                                   Path displayFile, String uri, List<Diagnostic> diagnostics) {
        String body = maskMarkup(xml.substring(bodyStart, bodyEnd));
        boolean inLiteral = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!inLiteral && body.startsWith("--", i)) {
                int newline = body.indexOf('\n', i);
                i = newline < 0 ? body.length() : newline;
            } else if (!inLiteral && body.startsWith("/*", i)) {
                int close = body.indexOf("*/", i + 2);
                i = close < 0 ? body.length() : close + 1;
            } else if (c == '\'') {
                // '' は文字列の中の引用符なので、閉じてすぐ開いたのと同じに扱える。
                inLiteral = !inLiteral;
            } else if (body.startsWith("${", i)) {
                int close = body.indexOf('}', i);
                if (close < 0) {
                    return;
                }
                String expression = body.substring(i + 2, close).trim();
                Place place = inLiteral ? Place.LITERAL : placeBefore(body.substring(0, i));
                if (place != null && !isSafe(expression, context)) {
                    diagnostics.add(diagnostic(xml, bodyStart + i, bodyStart + close + 1, expression, place,
                            displayFile, uri));
                }
                i = close;
            }
        }
    }

    private static Place placeBefore(String before) {
        if (OPERATOR_BEFORE.matcher(before).find()) {
            return Place.VALUE;
        }
        return IN_LIST_BEFORE.matcher(before).find() ? Place.IN_LIST : null;
    }

    private boolean isSafe(String expression, StatementContext context) {
        if (expression.isEmpty() || expression.startsWith("@")) {
            return true;
        }
        if (!NAME.matcher(expression).matches()) {
            return false;
        }
        String type = types.typeOf(expression, context);
        return type != null && SAFE_TYPES.contains(type);
    }

    private static Diagnostic diagnostic(String xml, int start, int end, String expression, Place place,
                                         Path displayFile, String uri) {
        String written = xml.substring(start, end);
        String parameter = "#{" + expression + "}";
        String message = written + " is inserted into the SQL as is, so a value from user input can change the SQL "
                + "(SQL injection); " + switch (place) {
                    case VALUE -> "use " + parameter + ".";
                    case LITERAL -> "use " + parameter + " outside the quotes, e.g. '%' || " + parameter + " || '%'.";
                    case IN_LIST -> "build the list with <foreach> and " + parameter.replace(expression, "item") + ".";
                };
        // 値の場所なら ${...} を #{...} に置き換えるだけで直る。文字列や IN の中は書き換えが要る。
        String suggestion = place == Place.VALUE ? parameter : null;
        return new Diagnostic(displayFile, uri, XmlPositions.range(xml, start, end), Severity.WARNING,
                RuleIds.UNSAFE_DOLLAR_SUBSTITUTION, message, suggestion);
    }

    /**
     * XML のコメントとタグ(属性の中の {@code ${...}} は SQL ではない)を空白にし、CDATA の
     * 区切りも空白にする。位置は変えない。
     */
    private static String maskMarkup(String body) {
        StringBuilder masked = new StringBuilder(body);
        boolean inCdata = false;
        for (int i = 0; i < body.length(); i++) {
            if (!inCdata && body.startsWith("<![CDATA[", i)) {
                inCdata = true;
                i = blank(masked, i, i + 9) - 1;
            } else if (inCdata && body.startsWith("]]>", i)) {
                inCdata = false;
                i = blank(masked, i, i + 3) - 1;
            } else if (!inCdata && body.startsWith("<!--", i)) {
                int close = body.indexOf("-->", i);
                i = blank(masked, i, close < 0 ? body.length() : close + 3) - 1;
            } else if (!inCdata && body.charAt(i) == '<') {
                // 属性値の中には > を書ける(test="a > 1")ので、引用符の中の > では閉じない。
                int close = XmlPositions.findStartTagEnd(body, i);
                i = blank(masked, i, close < 0 ? body.length() : close + 1) - 1;
            }
        }
        return masked.toString();
    }

    private static int blank(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (text.charAt(i) != '\n') {
                text.setCharAt(i, ' ');
            }
        }
        return to;
    }
}
