package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Position;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Comment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <select resultType="...">} の SELECT 句で {@code AS} を付けた列の別名が、
 * resultType のクラスのプロパティに対応しているかを見る({@code mybatis-lint/unknown-select-alias})。
 *
 * <p>MyBatis は resultType の列を自動マッピングするが、対応するプロパティがない列は
 * 標準の設定では黙って捨てる。そのため別名のタイプミスは実行時にも気づきにくい。</p>
 *
 * <h2>判断できない場合は何もしない</h2>
 *
 * <p>本格的な SQL パーサーは使わず、SELECT から最初の FROM までを読むだけにしている。
 * 次の場合は列が確定しないので、その statement は検証しない。</p>
 * <ul>
 *   <li>FROM より前に {@code <include>} や {@code <if>} などの要素がある</li>
 *   <li>{@code *} や {@code t.*} がある、{@code ${...}} がある</li>
 *   <li>SELECT で始まらない(WITH 句など)、FROM が見つからない</li>
 *   <li>resultType のクラスやそのプロパティを決められない</li>
 * </ul>
 * <p>{@code AS} のない列は、列名とプロパティ名の対応が {@code mapUnderscoreToCamelCase}
 * の設定で変わるので検証しない。</p>
 *
 * <h2>名前の比べ方</h2>
 *
 * <p>MyBatis の自動マッピングは大文字小文字を区別しない(Oracle は引用符のない別名を
 * 大文字で返すが、それでも対応する)。{@code mapUnderscoreToCamelCase} が有効なら
 * {@code user_name} が {@code userName} に対応する。設定は読まないので、大文字小文字と
 * アンダースコアを無視して比べ、どちらの設定でも対応しうる名前は警告しない。</p>
 */
public final class MapperSelectAliasValidator {

    private static final Pattern ALIAS_NAME = Pattern.compile("[A-Za-z_$#][A-Za-z0-9_$#]*");
    // 別名のない列: COLUMN、t.COLUMN、"Column"(t. は付いていてもよい)。
    private static final Pattern PLAIN_COLUMN = Pattern.compile(
            "(?:[A-Za-z_$#][A-Za-z0-9_$#]*\\.){0,2}(?:([A-Za-z_$#][A-Za-z0-9_$#]*)|\"([^\"]+)\")");

    private final JavaTypeIndex typeIndex;

    public MapperSelectAliasValidator(JavaTypeIndex typeIndex) {
        this.typeIndex = typeIndex == null ? JavaTypeIndex.empty() : typeIndex;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        if (typeIndex.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            List<Diagnostic> diagnostics = new ArrayList<>();
            int searchOffset = 0;
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element select) || !"select".equals(select.getTagName())) {
                    continue;
                }
                int elementStart = findSelectStart(xml, select, searchOffset);
                if (elementStart < 0) {
                    // 位置がわからないと、後続の statement の位置もずれる。ここで打ち切る。
                    break;
                }
                searchOffset = elementStart + "<select".length();
                validateSelect(select, xml, elementStart, displayFile, uri, diagnostics);
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    private void validateSelect(Element select, String xml, int elementStart, Path displayFile, String uri,
                                List<Diagnostic> diagnostics) {
        if (!select.getAttribute("resultMap").isBlank()) {
            return;
        }
        String resultType = select.getAttribute("resultType").trim();
        if (resultType.isEmpty() || resultType.contains("${")) {
            return;
        }
        String qualifiedName = typeIndex.resolveTypeReference(resultType);
        Set<String> properties = qualifiedName == null ? null : typeIndex.findResultProperties(qualifiedName);
        if (properties == null) {
            return;
        }
        List<String> aliases = extractSelectAliases(leadingSql(select));
        if (aliases == null) {
            return;
        }
        Set<String> normalizedProperties = new HashSet<>();
        properties.forEach(property -> normalizedProperties.add(normalize(property)));

        int statementEnd = xml.indexOf("</select>", elementStart);
        int cursor = elementStart;
        for (String alias : aliases) {
            int[] span = findAlias(xml, alias, cursor, statementEnd < 0 ? xml.length() : statementEnd);
            if (span != null) {
                cursor = span[1];
            }
            if (normalizedProperties.contains(normalize(alias))) {
                continue;
            }
            Range range;
            if (span != null) {
                range = XmlPositions.range(xml, span[0], span[1]);
            } else {
                Position position = XmlPositions.toPosition(xml, elementStart);
                range = Range.at(position.getLine(), position.getCharacter());
            }
            // 別名はプロパティ名と大文字小文字・アンダースコアを無視して対応するので、同じ比べ方で探す。
            String suggestion = span == null ? null
                    : NameSuggestions.closest(alias, properties, MapperSelectAliasValidator::normalize);
            diagnostics.add(new Diagnostic(displayFile, uri, range, Severity.WARNING,
                    RuleIds.UNKNOWN_SELECT_ALIAS,
                    "Select column alias '" + alias + "' does not match any property of resultType '"
                            + qualifiedName + "'; MyBatis will ignore this column."
                            + NameSuggestions.didYouMean(suggestion), suggestion));
        }
    }

    /**
     * statement の先頭から、最初の子要素({@code <include>} や {@code <where>} など)の
     * 直前までの SQL。CDATA もテキストとして含め、XML コメントは除く。
     */
    static String leadingSql(Element select) {
        StringBuilder sql = new StringBuilder();
        NodeList children = select.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Text text) {
                sql.append(text.getData());
            } else if (child instanceof Comment) {
                continue;
            } else if (child instanceof Element) {
                break;
            }
        }
        return sql.toString();
    }

    /**
     * SELECT 句の列のうち、AS で付けた別名を順に返す。列が確定しない場合は null。
     */
    static List<String> extractSelectAliases(String rawSql) {
        List<String> items = splitSelectItems(rawSql);
        if (items == null) {
            return null;
        }
        List<String> aliases = new ArrayList<>();
        for (String item : items) {
            String alias = explicitAlias(item);
            if (alias != null) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    /**
     * SELECT 句の列が結果で持つ名前(ラベル)を順に返す。AS の別名、なければ
     * {@code t.COLUMN} や {@code COLUMN} の列名。式に別名がないなど、ラベルを
     * 決められない列が 1 つでもあれば null。
     */
    static List<String> extractSelectLabels(String rawSql) {
        List<String> items = splitSelectItems(rawSql);
        if (items == null) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String item : items) {
            String label = explicitAlias(item);
            if (label == null) {
                Matcher column = PLAIN_COLUMN.matcher(item);
                if (!column.matches()) {
                    return null;
                }
                label = column.group(1) != null ? column.group(1) : column.group(2);
            }
            labels.add(label);
        }
        return labels;
    }

    /** SELECT から最初の FROM までの列の式。列が確定しない場合は null。 */
    private static List<String> splitSelectItems(String rawSql) {
        String sql = stripComments(rawSql).trim();
        if (!startsWithKeyword(sql, 0, "select")) {
            return null;
        }
        int index = "select".length();
        index = skipWhitespace(sql, index);
        for (String modifier : List.of("distinct", "unique", "all")) {
            if (startsWithKeyword(sql, index, modifier)) {
                index = skipWhitespace(sql, index + modifier.length());
                break;
            }
        }

        List<String> items = new ArrayList<>();
        int depth = 0;
        int itemStart = index;
        boolean foundFrom = false;
        for (int i = index; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ',') {
                items.add(sql.substring(itemStart, i));
                itemStart = i + 1;
            } else if (depth == 0 && startsWithKeyword(sql, i, "from")) {
                items.add(sql.substring(itemStart, i));
                foundFrom = true;
                break;
            }
        }
        if (!foundFrom) {
            return null;
        }

        List<String> trimmed = new ArrayList<>();
        for (String rawItem : items) {
            String item = rawItem.trim();
            if (item.isEmpty() || item.equals("*") || item.endsWith(".*") || item.contains("${")
                    || item.contains("#{")) {
                return null;
            }
            trimmed.add(item);
        }
        return trimmed;
    }

    /** 列の式のうち、最後の(括弧の外の)AS のあとの別名。AS がなければ null。 */
    private static String explicitAlias(String item) {
        int depth = 0;
        int lastAs = -1;
        for (int i = 0; i < item.length(); i++) {
            char c = item.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(item, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && startsWithKeyword(item, i, "as")) {
                lastAs = i;
            }
        }
        if (lastAs < 0) {
            return null;
        }
        String alias = item.substring(lastAs + 2).trim();
        if (alias.length() >= 2 && alias.startsWith("\"") && alias.endsWith("\"")) {
            alias = alias.substring(1, alias.length() - 1);
        }
        // a.b のような入れ子のプロパティや、記号を含む名前は対象外。
        return ALIAS_NAME.matcher(alias).matches() ? alias : null;
    }

    private static String stripComments(String sql) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(sql, i);
                result.append(sql, i, Math.min(end + 1, sql.length()));
                i = end;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline - 1;
                result.append(' ');
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                // Oracle のヒント句(/*+ ... */)もここで消える。
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 1;
                result.append(' ');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /** 引用符で囲まれた部分の閉じ引用符の位置。'' のような重ねた引用符も中身として扱う。 */
    private static int skipQuoted(String text, int start) {
        char quote = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return text.length();
    }

    private static boolean startsWithKeyword(String text, int index, String keyword) {
        if (!text.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        boolean boundaryBefore = index == 0 || !isIdentifierChar(text.charAt(index - 1));
        int after = index + keyword.length();
        boolean boundaryAfter = after >= text.length() || !isIdentifierChar(text.charAt(after));
        return boundaryBefore && boundaryAfter;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '.';
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /** 元の XML から {@code AS 別名} の別名部分を探す。見つからなければ null。 */
    private static int[] findAlias(String xml, String alias, int from, int to) {
        Matcher matcher = Pattern.compile("(?i)(?<![A-Za-z0-9_$#])AS\\s+\"?(" + Pattern.quote(alias)
                + ")\"?(?![A-Za-z0-9_$#])").matcher(xml);
        matcher.region(Math.min(from, to), to);
        if (!matcher.find()) {
            return null;
        }
        return new int[] {matcher.start(1), matcher.end(1)};
    }

    static int findSelectStart(String xml, Element select, int from) {
        String id = select.getAttribute("id");
        Matcher matcher = Pattern.compile("<select(?=[\\s>/])").matcher(xml);
        int index = from;
        while (matcher.find(index)) {
            int start = matcher.start();
            if (id.isEmpty() || XmlPositions.findAttributeValueStart(xml, start, "id", id) >= 0) {
                return start;
            }
            index = matcher.end();
        }
        return -1;
    }
}
