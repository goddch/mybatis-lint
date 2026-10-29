package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL の文字列リテラル({@code '...'})の中に書いた {@code #{...}} を見つける
 * ({@code mybatis-lint/parameter-in-string-literal})。
 *
 * <p>{@code LIKE '%#{name}%'} のように書くと、MyBatis は {@code #{name}} を {@code ?} に
 * 置き換えるが、文字列の中の {@code ?} はバインド変数として扱われない。そのため実行時に
 * 「パラメータの位置が不正」といったエラーになる(Oracle なら invalid column index)。
 * 正しくは {@code LIKE '%' || #{name} || '%'} のように文字列の外に出す。</p>
 *
 * <p>引用符の対応がとれない statement(コメントの中の {@code '} を読み違えた場合などを含む)や、
 * Oracle の {@code q'[...]'} を使った statement は、読み違えの可能性があるので検証しない。
 * {@code ${...}} は文字列にそのまま埋め込まれるので、文字列の中に書いても正しい。</p>
 */
public final class MapperStringLiteralParameterValidator {

    private static final Set<String> TARGET_TAGS = Set.of("select", "insert", "update", "delete", "sql");
    private static final Pattern PARAMETER = Pattern.compile("#\\{[^}]*\\}");
    private static final Pattern Q_QUOTE = Pattern.compile("(?i)(?<![A-Za-z0-9_$#])n?q'");

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            List<Diagnostic> diagnostics = new ArrayList<>();
            int cursor = 0;
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element statement) || !TARGET_TAGS.contains(statement.getTagName())) {
                    continue;
                }
                int start = xml.indexOf("<" + statement.getTagName(), cursor);
                if (start < 0) {
                    break;
                }
                cursor = start + 1;
                int end = xml.indexOf("</" + statement.getTagName() + ">", start);
                validateStatement(statement.getTextContent(), xml, start, end < 0 ? xml.length() : end,
                        displayFile, uri, diagnostics);
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    /** statement の中の {@code #{...}} と、それが文字列リテラルの中にあるか。 */
    private record ParameterOccurrence(String text, boolean insideLiteral) {
    }

    private static void validateStatement(String sql, String xml, int statementStart, int statementEnd,
                                          Path displayFile, String uri, List<Diagnostic> diagnostics) {
        if (Q_QUOTE.matcher(sql).find()) {
            return;
        }
        List<ParameterOccurrence> occurrences = new ArrayList<>();
        boolean inLiteral = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '#' && i + 1 < sql.length() && sql.charAt(i + 1) == '{') {
                Matcher matcher = PARAMETER.matcher(sql);
                if (matcher.find(i) && matcher.start() == i) {
                    occurrences.add(new ParameterOccurrence(matcher.group(), inLiteral));
                    i = matcher.end() - 1;
                }
            } else if (inLiteral) {
                // '' は文字列の中の引用符なので、閉じてすぐ開いたのと同じに扱える。
                if (c == '\'') {
                    inLiteral = false;
                }
            } else if (c == '\'') {
                inLiteral = true;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 1;
            }
        }
        // 引用符が閉じていなければ、どこかで読み違えている。
        if (inLiteral || occurrences.stream().noneMatch(ParameterOccurrence::insideLiteral)) {
            return;
        }

        // 元の XML 上の位置は、statement の中の #{...} を先頭から順に探して求める。
        // 文字列の外にあるものも順に読み飛ばすことで、同じ #{...} が内外にあっても取り違えない。
        int cursor = statementStart;
        for (ParameterOccurrence occurrence : occurrences) {
            String parameter = occurrence.text();
            int found = xml.indexOf(parameter, cursor);
            boolean located = found >= 0 && found < statementEnd;
            if (located) {
                cursor = found + parameter.length();
            }
            if (!occurrence.insideLiteral()) {
                continue;
            }
            diagnostics.add(new Diagnostic(displayFile, uri,
                    located ? XmlPositions.range(xml, found, found + parameter.length())
                            : XmlPositions.range(xml, statementStart, statementStart),
                    Severity.ERROR, RuleIds.PARAMETER_IN_STRING_LITERAL,
                    "Parameter " + parameter + " is inside a SQL string literal, so it is not bound; "
                            + "concatenate it instead, e.g. '%' || " + parameter + " || '%'."));
        }
    }
}
