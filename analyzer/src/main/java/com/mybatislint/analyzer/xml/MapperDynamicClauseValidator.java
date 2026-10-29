package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <where>} / {@code <set>} を使わずに、SQL の {@code WHERE} / {@code SET} のあとへ
 * {@code <if>} で条件や項目を足している statement で、条件の組み合わせによって SQL が壊れるものを見つける。
 *
 * <h2>{@code mybatis-lint/dangling-sql-joiner}</h2>
 * <p>{@code WHERE <if>AND a = 1</if>} は、その {@code <if>} が成り立つと {@code WHERE AND a = 1} になる。
 * {@code SET <if>a = 1,</if> WHERE} は {@code a = 1, WHERE} になる。どの条件が成り立っても必ず壊れる
 * ものは error、ほかの条件の成り立ち方によって壊れるものは warning にする。</p>
 *
 * <h2>{@code mybatis-lint/empty-dynamic-clause}(warning)</h2>
 * <p>{@code WHERE} / {@code SET} のあとがすべて {@code <if>} だと、全部が成り立たないときに
 * {@code WHERE} だけが残る。呼び出し側が必ずどれかを渡すなら問題ないので warning にする。</p>
 *
 * <h2>判断できない場合は何もしない</h2>
 * <ul>
 *   <li>{@code <include>}、{@code <foreach>}、{@code <trim>} などが間にあると、そこから先は見ない
 *       (中身や、空になるかがわからない)</li>
 *   <li>{@code <sql>} や {@code <if>} の中で終わる WHERE は、続きが外にあるので末尾の検証をしない</li>
 *   <li>{@code <if>} の先頭や末尾が別の要素のときは、その側の AND / カンマを見ない</li>
 * </ul>
 */
public final class MapperDynamicClauseValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> CONTAINER_TAGS =
            Set.of("select", "insert", "update", "delete", "sql", "if", "when", "otherwise");

    private static final String WORD_START = "(?<![A-Za-z0-9_$#.])";
    private static final String WORD_END = "(?![A-Za-z0-9_$#])";

    /** WHERE 句と SET 句の違い。 */
    private enum Clause {
        WHERE("WHERE", "<where>",
                Pattern.compile("(?i)" + WORD_START + "WHERE" + WORD_END),
                Pattern.compile("(?i)^(AND|OR)" + WORD_END),
                Pattern.compile("(?i)" + WORD_START + "(AND|OR)$"),
                Pattern.compile("(?i)" + WORD_START + "(ORDER\\s+BY|GROUP\\s+BY|HAVING|UNION|MINUS|INTERSECT"
                        + "|FOR\\s+UPDATE|FETCH|OFFSET|LIMIT|CONNECT\\s+BY|START\\s+WITH|RETURNING)" + WORD_END)),
        SET("SET", "<set>",
                Pattern.compile("(?i)" + WORD_START + "SET" + WORD_END),
                Pattern.compile("^,"),
                Pattern.compile(",$"),
                Pattern.compile("(?i)" + WORD_START + "(WHERE|RETURNING)" + WORD_END));

        final String keyword;
        final String tag;
        final Pattern keywordPattern;
        final Pattern leadingJoiner;
        final Pattern trailingJoiner;
        final Pattern terminator;

        Clause(String keyword, String tag, Pattern keywordPattern, Pattern leadingJoiner, Pattern trailingJoiner,
               Pattern terminator) {
            this.keyword = keyword;
            this.tag = tag;
            this.keywordPattern = keywordPattern;
            this.leadingJoiner = leadingJoiner;
            this.trailingJoiner = trailingJoiner;
            this.terminator = terminator;
        }
    }

    /**
     * 句の中身の 1 かたまり。{@code conditional} は {@code <if>} のように出力されないことがあるもの。
     * {@code element} はテキストなら null。
     */
    private record Piece(Element element, boolean conditional, boolean leadingJoiner, boolean trailingJoiner) {
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            Map<Element, Integer> offsets = new IdentityHashMap<>();
            locateElements(mapper, xml, new int[] {0}, offsets);
            List<Diagnostic> diagnostics = new ArrayList<>();
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element statement
                        && (STATEMENT_TAGS.contains(statement.getTagName()) || "sql".equals(statement.getTagName()))) {
                    visit(statement, xml, offsets, displayFile, uri, diagnostics);
                }
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    private void visit(Element container, String xml, Map<Element, Integer> offsets, Path displayFile, String uri,
                       List<Diagnostic> diagnostics) {
        List<Node> nodes = childNodes(container);
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof Text text) {
                for (Clause clause : Clause.values()) {
                    checkClause(clause, text, container, nodes.subList(i + 1, nodes.size()), xml, offsets,
                            displayFile, uri, diagnostics);
                }
            } else if (node instanceof Element child && CONTAINER_TAGS.contains(child.getTagName())) {
                visit(child, xml, offsets, displayFile, uri, diagnostics);
            } else if (node instanceof Element child && "choose".equals(child.getTagName())) {
                visit(child, xml, offsets, displayFile, uri, diagnostics);
            }
        }
    }

    private void checkClause(Clause clause, Text start, Element container, List<Node> following, String xml,
                             Map<Element, Integer> offsets, Path displayFile, String uri,
                             List<Diagnostic> diagnostics) {
        String text = clean(start.getData());
        Matcher keyword = clause.keywordPattern.matcher(text);
        int keywordEnd = -1;
        while (keyword.find()) {
            keywordEnd = keyword.end();
        }
        if (keywordEnd < 0) {
            return;
        }
        String remainder = text.substring(keywordEnd);
        if (terminatorIndex(clause, remainder) >= 0) {
            // 句はこのテキストの中で終わっている。<if> は関係しない。
            return;
        }
        List<Piece> pieces = new ArrayList<>();
        if (!remainder.isBlank()) {
            pieces.add(textPiece(clause, remainder));
        }
        boolean hasElement = false;
        // 句の終わりがわかったか。わからなければ、末尾のカンマや空になる場合は判断しない。
        boolean endKnown = false;
        boolean stopped = false;
        for (Node node : following) {
            if (node instanceof Comment) {
                continue;
            }
            if (node instanceof Text text1) {
                String data = clean(text1.getData());
                int end = terminatorIndex(clause, data);
                String before = end < 0 ? data : data.substring(0, end);
                if (!before.isBlank()) {
                    pieces.add(textPiece(clause, before));
                }
                if (end >= 0) {
                    endKnown = true;
                    break;
                }
                continue;
            }
            if (!(node instanceof Element element)) {
                continue;
            }
            if ("bind".equals(element.getTagName())) {
                continue;
            }
            Piece piece = elementPiece(clause, element);
            if (piece == null) {
                stopped = true;
                break;
            }
            hasElement = true;
            pieces.add(piece);
        }
        if (!hasElement) {
            // <if> などがなければ静的な SQL なので、この validator の対象ではない。
            return;
        }
        if (!stopped && !endKnown) {
            endKnown = STATEMENT_TAGS.contains(container.getTagName());
        }

        int keywordOffset = keywordOffset(clause, xml, container, following, offsets);
        // 前の要素がすべて <if> なら、その要素が句の先頭になりうる。
        boolean onlyConditionalBefore = true;
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            if (piece.leadingJoiner() && onlyConditionalBefore) {
                boolean certain = i == 0;
                diagnostics.add(diagnostic(xml, offsets, piece, true, keywordOffset, clause, displayFile, uri,
                        certain ? Severity.ERROR : Severity.WARNING,
                        (certain ? "When this is included, the SQL becomes '" : "If every condition before this is false, the SQL becomes '")
                                + clause.keyword + " " + leadingText(clause) + " ...'; use " + clause.tag
                                + ", which removes the leading " + leadingText(clause) + "."));
            }
            onlyConditionalBefore &= piece.conditional();
        }
        if (endKnown) {
            boolean onlyConditionalAfter = true;
            for (int i = pieces.size() - 1; i >= 0; i--) {
                Piece piece = pieces.get(i);
                if (piece.trailingJoiner() && onlyConditionalAfter) {
                    boolean certain = i == pieces.size() - 1 && piece.conditional();
                    diagnostics.add(diagnostic(xml, offsets, piece, false, keywordOffset, clause, displayFile, uri,
                            certain ? Severity.ERROR : Severity.WARNING,
                            (certain ? "When this is included, the " : "If every condition after this is false, the ")
                                    + clause.keyword + " clause ends with '" + trailingText(clause) + "'; "
                                    + trailingAdvice(clause)));
                }
                onlyConditionalAfter &= piece.conditional();
            }
            if (pieces.stream().allMatch(Piece::conditional)) {
                diagnostics.add(new Diagnostic(displayFile, uri, keywordRange(xml, keywordOffset, clause),
                        Severity.WARNING, RuleIds.EMPTY_DYNAMIC_CLAUSE,
                        "If every condition is false, nothing follows " + clause.keyword + "; use " + clause.tag
                                + ", which leaves out " + clause.keyword + " when it would be empty."));
            }
        }
    }

    private static String leadingText(Clause clause) {
        return clause == Clause.WHERE ? "AND/OR" : ",";
    }

    private static String trailingText(Clause clause) {
        return clause == Clause.WHERE ? "AND/OR" : ",";
    }

    private static String trailingAdvice(Clause clause) {
        // <where> は先頭の AND / OR しか取り除かない。<set> は末尾のカンマも取り除く。
        return clause == Clause.WHERE
                ? "move AND/OR to the start of the next condition and use <where>."
                : "use <set>, which removes the trailing comma.";
    }

    private static Piece textPiece(Clause clause, String text) {
        String trimmed = text.strip();
        return new Piece(null, false, clause.leadingJoiner.matcher(trimmed).find(),
                clause.trailingJoiner.matcher(trimmed).find());
    }

    /** {@code <if>} と {@code <choose>} だけを読む。それ以外の要素は null(そこで読むのをやめる)。 */
    private static Piece elementPiece(Clause clause, Element element) {
        if ("if".equals(element.getTagName())) {
            String[] edges = edgeTexts(clause, element);
            if (edges == null) {
                return null;
            }
            return new Piece(element, true, matches(clause.leadingJoiner, edges[0]),
                    matches(clause.trailingJoiner, edges[1]));
        }
        if (!"choose".equals(element.getTagName())) {
            return null;
        }
        boolean leading = false;
        boolean trailing = false;
        boolean alwaysOutput = false;
        for (Node node : childNodes(element)) {
            if (!(node instanceof Element branch)) {
                continue;
            }
            String[] edges = edgeTexts(clause, branch);
            if (edges == null) {
                return null;
            }
            leading |= matches(clause.leadingJoiner, edges[0]);
            trailing |= matches(clause.trailingJoiner, edges[1]);
            if ("otherwise".equals(branch.getTagName()) && !branch.getTextContent().isBlank()) {
                alwaysOutput = true;
            }
        }
        return new Piece(element, !alwaysOutput, leading, trailing);
    }

    /**
     * 要素の先頭と末尾のテキスト(先頭や末尾が子要素なら null の代わりに空文字)。
     * 中で句が終わっている(ORDER BY など)なら、句の続きがわからないので null。
     */
    private static String[] edgeTexts(Clause clause, Element element) {
        if (terminatorIndex(clause, clean(element.getTextContent())) >= 0) {
            return null;
        }
        List<Node> nodes = childNodes(element);
        StringBuilder leading = new StringBuilder();
        for (Node node : nodes) {
            if (node instanceof Element) {
                break;
            }
            if (node instanceof Text text) {
                leading.append(text.getData());
            }
        }
        StringBuilder trailing = new StringBuilder();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node node = nodes.get(i);
            if (node instanceof Element) {
                break;
            }
            if (node instanceof Text text) {
                trailing.insert(0, text.getData());
            }
        }
        return new String[] {clean(leading.toString()).strip(), clean(trailing.toString()).strip()};
    }

    private static boolean matches(Pattern pattern, String text) {
        return !text.isEmpty() && pattern.matcher(text).find();
    }

    /** 括弧の外にある、句の終わりの位置。閉じ括弧(サブクエリの終わり)も句の終わりとみなす。なければ -1。 */
    private static int terminatorIndex(Clause clause, String text) {
        int depth = 0;
        Matcher matcher = clause.terminator.matcher(text).useTransparentBounds(true);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (depth == 0) {
                if (matcher.region(i, text.length()).lookingAt()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** SQL のコメントを空白に、文字列リテラルを空の文字列にする。中の WHERE などを読まないため。 */
    private static String clean(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("'(?:[^']|'')*'", "''");
    }

    private static List<Node> childNodes(Element element) {
        List<Node> nodes = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            nodes.add(children.item(i));
        }
        return nodes;
    }

    private static Diagnostic diagnostic(String xml, Map<Element, Integer> offsets, Piece piece, boolean leading,
                                         int keywordOffset, Clause clause, Path displayFile, String uri, Severity severity, String message) {
        Range range = keywordRange(xml, keywordOffset, clause);
        Integer start = piece.element() == null ? null : offsets.get(piece.element());
        if (start != null) {
            range = XmlPositions.range(xml, start, start + 1 + piece.element().getTagName().length());
            // 先頭の AND / カンマは、要素の中のその文字を指す。
            int tagEnd = XmlPositions.findStartTagEnd(xml, start);
            if (leading && "if".equals(piece.element().getTagName()) && tagEnd >= 0) {
                Matcher joiner = Pattern.compile(clause == Clause.WHERE ? "(?i)" + WORD_START + "(AND|OR)" + WORD_END : ",")
                        .matcher(xml);
                if (joiner.find(tagEnd)) {
                    range = XmlPositions.range(xml, joiner.start(), joiner.end());
                }
            }
        }
        return new Diagnostic(displayFile, uri, range, severity, RuleIds.DANGLING_SQL_JOINER, message);
    }

    private static Range keywordRange(String xml, int keywordOffset, Clause clause) {
        return XmlPositions.range(xml, keywordOffset, keywordOffset + (keywordOffset > 0 ? clause.keyword.length() : 0));
    }

    /**
     * 句のキーワードの位置。DOM ではテキストの位置がわからないので、キーワードのあとにある
     * 最初の要素の位置から、その手前で最後に現れるキーワードを探す。
     */
    private static int keywordOffset(Clause clause, String xml, Element container, List<Node> following,
                                     Map<Element, Integer> offsets) {
        int from = offsets.getOrDefault(container, 0);
        int to = xml.length();
        for (Node node : following) {
            if (node instanceof Element element && offsets.containsKey(element)) {
                to = offsets.get(element);
                break;
            }
        }
        Matcher matcher = clause.keywordPattern.matcher(xml);
        matcher.region(Math.min(from, to), to);
        int found = -1;
        while (matcher.find()) {
            found = matcher.start();
        }
        return Math.max(found, from);
    }

    /** 文書の順に開始タグを探し、要素ごとの位置を覚える。 */
    private static void locateElements(Element element, String xml, int[] cursor, Map<Element, Integer> offsets) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            Matcher matcher = Pattern.compile("<" + Pattern.quote(child.getTagName()) + "(?=[\\s>/])").matcher(xml);
            if (matcher.find(cursor[0])) {
                cursor[0] = matcher.start() + 1;
                offsets.put(child, matcher.start());
            }
            locateElements(child, xml, cursor, offsets);
        }
    }
}
