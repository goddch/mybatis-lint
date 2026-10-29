package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code INSERT INTO table (列, ...)} の列の数と、{@code VALUES (...)} の値や
 * {@code SELECT ...} の列の数が合わないものを見つける({@code mybatis-lint/insert-column-count})。
 * Oracle では ORA-00947(値が足りない)/ ORA-00913(値が多すぎる)で必ず失敗する。
 *
 * <h2>判断できない場合は何もしない</h2>
 * <ul>
 *   <li>列の一覧、VALUES、SELECT 句の途中に {@code <if>} や {@code <include>} などの要素がある
 *       (statement の先頭から最初の子要素までで、列と値が読み切れるものだけを見る)</li>
 *   <li>列の一覧がない INSERT、SELECT 句に {@code *} や {@code ${...}} がある</li>
 * </ul>
 */
public final class MapperInsertColumnCountValidator {

    private static final Pattern INSERT_INTO =
            Pattern.compile("(?i)(?<![A-Za-z0-9_$#])INSERT\\s+INTO\\s+[A-Za-z0-9_$#.\"@]+\\s*\\(");
    private static final Pattern VALUES = Pattern.compile("(?i)\\s*VALUES\\s*\\(");
    private static final Pattern SELECT =
            Pattern.compile("(?i)\\s*SELECT(?![A-Za-z0-9_$#])(\\s+(DISTINCT|UNIQUE|ALL)(?![A-Za-z0-9_$#]))?");
    private static final Pattern FROM = Pattern.compile("(?i)(?<![A-Za-z0-9_$#.])FROM(?![A-Za-z0-9_$#])");
    private static final Pattern INSERT_KEYWORD = Pattern.compile("(?i)(?<![A-Za-z0-9_$#])INSERT(?![A-Za-z0-9_$#])");

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
                if (!(children.item(i) instanceof Element insert) || !"insert".equals(insert.getTagName())) {
                    continue;
                }
                int start = findInsertStart(xml, insert, cursor);
                if (start >= 0) {
                    cursor = start + 1;
                }
                int[] counts = countColumnsAndValues(MapperSelectAliasValidator.leadingSql(insert));
                if (counts == null || counts[0] == counts[1]) {
                    continue;
                }
                Range range = XmlPositions.range(xml, Math.max(start, 0), Math.max(start, 0));
                int tagEnd = start < 0 ? -1 : XmlPositions.findStartTagEnd(xml, start);
                if (tagEnd >= 0) {
                    Matcher keyword = INSERT_KEYWORD.matcher(xml);
                    int end = xml.indexOf("</insert>", start);
                    if (keyword.find(tagEnd) && (end < 0 || keyword.start() < end)) {
                        range = XmlPositions.range(xml, keyword.start(), keyword.end());
                    }
                }
                diagnostics.add(new Diagnostic(displayFile, uri, range, Severity.ERROR, RuleIds.INSERT_COLUMN_COUNT,
                        "INSERT lists " + counts[0] + " column(s) but " + (counts[2] == 1 ? "VALUES has " : "SELECT returns ")
                                + counts[1] + (counts[2] == 1 ? " value(s)." : " column(s).")));
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    /**
     * 列の数、値の数、値が VALUES なら 1・SELECT なら 2。読み切れなければ null。
     */
    static int[] countColumnsAndValues(String rawSql) {
        String sql = mask(rawSql);
        Matcher insert = INSERT_INTO.matcher(sql);
        if (!insert.find()) {
            return null;
        }
        int columnsEnd = closingParen(sql, insert.end() - 1);
        if (columnsEnd < 0) {
            return null;
        }
        List<String> columns = split(sql, insert.end(), columnsEnd);
        if (columns == null) {
            return null;
        }
        Matcher values = VALUES.matcher(sql).region(columnsEnd + 1, sql.length());
        if (values.lookingAt()) {
            int valuesEnd = closingParen(sql, values.end() - 1);
            List<String> items = valuesEnd < 0 ? null : split(sql, values.end(), valuesEnd);
            return items == null ? null : new int[] {columns.size(), items.size(), 1};
        }
        Matcher select = SELECT.matcher(sql).region(columnsEnd + 1, sql.length());
        if (!select.lookingAt()) {
            return null;
        }
        int from = topLevelFrom(sql, select.end());
        List<String> items = from < 0 ? null : split(sql, select.end(), from);
        if (items == null || items.stream().anyMatch(item -> item.equals("*") || item.endsWith(".*"))) {
            return null;
        }
        return new int[] {columns.size(), items.size(), 2};
    }

    /**
     * コメントを空白に、文字列リテラルと {@code #{...}} の中身を {@code x} にする(中のカンマや括弧で
     * 区切りを読み違えないため)。{@code ${...}} は中身が SQL になるので、見つけたら読まない。
     */
    private static String mask(String sql) {
        StringBuilder masked = new StringBuilder(sql);
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '-' && sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = fill(masked, i, end < 0 ? sql.length() : end, ' ') - 1;
            } else if (c == '/' && sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = fill(masked, i, end < 0 ? sql.length() : end + 2, ' ') - 1;
            } else if (c == '\'') {
                int end = i + 1;
                while (end < sql.length() && (sql.charAt(end) != '\'' || sql.startsWith("''", end))) {
                    end += sql.startsWith("''", end) ? 2 : 1;
                }
                fill(masked, i + 1, Math.min(end, sql.length()), 'x');
                i = end;
            } else if (c == '#' && sql.startsWith("#{", i)) {
                int end = sql.indexOf('}', i);
                i = fill(masked, i + 2, end < 0 ? sql.length() : end, 'x');
            } else if (c == '$' && sql.startsWith("${", i)) {
                // 中身によって列や値の数が変わりうる。
                masked.setCharAt(i, '\u0000');
            }
        }
        return masked.toString();
    }

    private static int fill(StringBuilder text, int from, int to, char c) {
        for (int i = from; i < to; i++) {
            text.setCharAt(i, c);
        }
        return to;
    }

    private static int closingParen(String sql, int open) {
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            if (sql.charAt(i) == '(') {
                depth++;
            } else if (sql.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int topLevelFrom(String sql, int from) {
        int depth = 0;
        Matcher matcher = FROM.matcher(sql).useTransparentBounds(true);
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && matcher.region(i, sql.length()).lookingAt()) {
                return i;
            }
        }
        return -1;
    }

    /** 括弧の外のカンマで区切る。空の項目や {@code ${...}} があれば null。 */
    private static List<String> split(String sql, int from, int to) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int itemStart = from;
        for (int i = from; i <= to; i++) {
            char c = i == to ? ',' : sql.charAt(i);
            if (c == '\u0000') {
                return null;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String item = sql.substring(itemStart, i).trim();
                if (item.isEmpty()) {
                    return null;
                }
                items.add(item);
                itemStart = i + 1;
            }
        }
        return items;
    }

    private static int findInsertStart(String xml, Element insert, int from) {
        String id = insert.getAttribute("id");
        Matcher matcher = Pattern.compile("<insert(?=[\\s>/])").matcher(xml);
        int index = from;
        while (matcher.find(index)) {
            if (id.isEmpty() || XmlPositions.findAttributeValueStart(xml, matcher.start(), "id", id) >= 0) {
                return matcher.start();
            }
            index = matcher.end();
        }
        return -1;
    }
}
