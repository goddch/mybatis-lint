package com.mybatislint.analyzer.xml;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 動的な要素を含まない statement から、DB に送られるのと同じ SQL を組み立てる。
 *
 * <p>構文の誤りを元の XML の位置で報告したいので、DOM ではなく元の XML の文字列から組み立て、
 * SQL の 1 文字ずつが XML のどこから来たかを覚えておく。</p>
 *
 * <p>組み立てるのは、SQL が 1 通りに決まる statement だけ。次のものを含む statement は、
 * 実行時の値で SQL が変わるので組み立てない(null を返す)。</p>
 * <ul>
 *   <li>{@code <if>}、{@code <where>}、{@code <foreach>} など、{@code <include>} と {@code <bind>} 以外の要素</li>
 *   <li>{@code ${...}}</li>
 *   <li>{@code <property>} を渡す {@code <include>}、別ファイルや見つからない {@code <sql>} への {@code <include>}</li>
 * </ul>
 * <p>{@code #{...}} は、JDBC に渡すときと同じく {@code ?} に置き換える。</p>
 */
final class StaticSqlBuilder {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Pattern REFID = Pattern.compile("\\brefid\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    // <include> の入れ子がこれより深いものは、循環の見落としを避けるために組み立てない。
    private static final int MAX_INCLUDE_DEPTH = 8;

    /** 組み立てた SQL と、SQL の i 文字目が XML の何文字目から来たか。 */
    record StaticSql(String sql, int[] xmlOffsets) {
        int xmlOffset(int sqlIndex) {
            if (xmlOffsets.length == 0) {
                return 0;
            }
            return xmlOffsets[Math.max(0, Math.min(sqlIndex, xmlOffsets.length - 1))];
        }
    }

    private final String xml;
    private final Element mapper;
    private final String namespace;

    StaticSqlBuilder(String xml, Element mapper) {
        this.xml = xml;
        this.mapper = mapper;
        this.namespace = mapper.getAttribute("namespace").trim();
    }

    /**
     * @param statementStart XML の中での statement の開始タグの位置
     * @return 組み立てた SQL。SQL が 1 通りに決まらない statement は null
     */
    StaticSql build(Element statement, int statementStart) {
        if (!STATEMENT_TAGS.contains(statement.getTagName())
                || "CALLABLE".equalsIgnoreCase(statement.getAttribute("statementType").trim())
                || !hasOnlyStaticChildren(statement, new HashSet<>(), 0)) {
            return null;
        }
        int[] content = contentRange(statement.getTagName(), statementStart);
        if (content == null) {
            return null;
        }
        Output out = new Output();
        if (!append(content[0], content[1], out, new HashSet<>(), 0)) {
            return null;
        }
        return out.trimmed();
    }

    /** {@code <include>} と {@code <bind>} 以外の要素がないか。{@code <include>} の先もたどる。 */
    private boolean hasOnlyStaticChildren(Element element, Set<String> visiting, int depth) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            switch (child.getTagName()) {
                case "bind" -> {
                    // 値を変数に入れるだけで、SQL には何も足さない。
                }
                case "include" -> {
                    if (child.hasChildNodes() && hasElementChild(child)) {
                        return false;
                    }
                    Element fragment = findLocalFragment(child.getAttribute("refid"));
                    String key = child.getAttribute("refid");
                    if (fragment == null || depth >= MAX_INCLUDE_DEPTH || !visiting.add(key)) {
                        return false;
                    }
                    boolean staticFragment = hasOnlyStaticChildren(fragment, visiting, depth + 1);
                    visiting.remove(key);
                    if (!staticFragment) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasElementChild(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    /** 同じファイルにある {@code <sql id>}。namespace 付きの refid は自分の namespace のときだけ探す。 */
    private Element findLocalFragment(String refid) {
        String id = refid == null ? "" : refid.trim();
        if (id.isEmpty() || id.contains("${")) {
            return null;
        }
        if (!namespace.isEmpty() && id.startsWith(namespace + ".")) {
            id = id.substring(namespace.length() + 1);
        }
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element
                    && "sql".equals(element.getTagName())
                    && id.equals(element.getAttribute("id").trim())) {
                return element;
            }
        }
        return null;
    }

    /** 開始タグの直後から終了タグの直前まで。中身のない {@code <select/>} は null。 */
    private int[] contentRange(String tagName, int elementStart) {
        if (elementStart < 0) {
            return null;
        }
        int startTagEnd = XmlPositions.findStartTagEnd(xml, elementStart);
        if (startTagEnd < 0 || xml.charAt(startTagEnd - 1) == '/') {
            return null;
        }
        // 中に同じ名前の要素はない(静的な statement と <sql> には include と bind しかない)ので、次の終了タグが閉じタグ。
        int end = xml.indexOf("</" + tagName, startTagEnd + 1);
        return end < 0 ? null : new int[] {startTagEnd + 1, end};
    }

    /** XML の {@code [start, end)} を SQL にして足す。SQL にできないものがあれば false。 */
    private boolean append(int start, int end, Output out, Set<String> visiting, int depth) {
        int i = start;
        while (i < end) {
            if (xml.startsWith("<!--", i)) {
                int close = xml.indexOf("-->", i + 4);
                if (close < 0 || close >= end) {
                    return false;
                }
                // コメントの前後の語がつながらないよう、空白 1 つにする。
                out.append(' ', i);
                i = close + 3;
            } else if (xml.startsWith("<![CDATA[", i)) {
                int close = xml.indexOf("]]>", i + 9);
                if (close < 0 || close >= end || !appendText(i + 9, close, false, out)) {
                    return false;
                }
                i = close + 3;
            } else if (xml.startsWith("<include", i)) {
                int tagEnd = XmlPositions.findStartTagEnd(xml, i);
                if (tagEnd < 0 || tagEnd >= end) {
                    return false;
                }
                Matcher refid = REFID.matcher(xml.substring(i, tagEnd + 1));
                if (!refid.find()) {
                    return false;
                }
                String key = refid.group(2) != null ? refid.group(2) : refid.group(3);
                Element fragment = findLocalFragment(key);
                if (fragment == null || depth >= MAX_INCLUDE_DEPTH || !visiting.add(key)) {
                    return false;
                }
                int[] fragmentContent = contentRange("sql", findFragmentStart(fragment));
                boolean appended = fragmentContent != null
                        && append(fragmentContent[0], fragmentContent[1], out.spaced(i), visiting, depth + 1);
                visiting.remove(key);
                if (!appended) {
                    return false;
                }
                out.append(' ', tagEnd);
                i = xml.charAt(tagEnd - 1) == '/' ? tagEnd + 1 : skipPast(xml, "</include>", tagEnd + 1, end);
                if (i < 0) {
                    return false;
                }
            } else if (xml.startsWith("<bind", i)) {
                int tagEnd = XmlPositions.findStartTagEnd(xml, i);
                if (tagEnd < 0 || tagEnd >= end) {
                    return false;
                }
                i = xml.charAt(tagEnd - 1) == '/' ? tagEnd + 1 : skipPast(xml, "</bind>", tagEnd + 1, end);
                if (i < 0) {
                    return false;
                }
            } else if (xml.charAt(i) == '<') {
                return false;
            } else {
                int next = nextMarkup(i, end);
                if (!appendText(i, next, true, out)) {
                    return false;
                }
                i = next;
            }
        }
        return true;
    }

    private int nextMarkup(int from, int end) {
        int index = xml.indexOf('<', from);
        return index < 0 || index > end ? end : index;
    }

    private static int skipPast(String xml, String token, int from, int end) {
        int index = xml.indexOf(token, from);
        return index < 0 || index >= end ? -1 : index + token.length();
    }

    /** {@code <sql id>} の開始タグの位置。同じ id の {@code <sql>} は findLocalFragment で 1 つに決めている。 */
    private int findFragmentStart(Element fragment) {
        String id = fragment.getAttribute("id");
        int index = xml.indexOf("<sql", 0);
        while (index >= 0) {
            if (XmlPositions.findAttributeValueStart(xml, index, "id", id) >= 0) {
                return index;
            }
            index = xml.indexOf("<sql", index + 4);
        }
        return -1;
    }

    /**
     * テキスト部分を足す。{@code #{...}} は {@code ?} に、文字参照({@code &lt;} など)は元の文字にする。
     * CDATA の中は文字参照をそのまま残す。{@code ${...}} があれば false。
     */
    private boolean appendText(int start, int end, boolean decodeEntities, Output out) {
        int i = start;
        while (i < end) {
            if (xml.startsWith("${", i)) {
                return false;
            }
            if (xml.startsWith("#{", i)) {
                int close = xml.indexOf('}', i + 2);
                if (close < 0 || close >= end) {
                    return false;
                }
                out.append('?', i);
                i = close + 1;
                continue;
            }
            char c = xml.charAt(i);
            if (decodeEntities && c == '&') {
                int semicolon = xml.indexOf(';', i);
                if (semicolon < 0 || semicolon >= end || semicolon - i > 10) {
                    return false;
                }
                String decoded = decodeEntity(xml.substring(i + 1, semicolon));
                if (decoded == null) {
                    return false;
                }
                for (int k = 0; k < decoded.length(); k++) {
                    out.append(decoded.charAt(k), i);
                }
                i = semicolon + 1;
                continue;
            }
            out.append(c, i);
            i++;
        }
        return true;
    }

    private static String decodeEntity(String name) {
        switch (name) {
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "amp":
                return "&";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                break;
        }
        try {
            if (name.startsWith("#x") || name.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
            }
            if (name.startsWith("#")) {
                return new String(Character.toChars(Integer.parseInt(name.substring(1))));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /** 組み立て中の SQL。 */
    private static final class Output {
        private final StringBuilder sql = new StringBuilder();
        private int[] offsets = new int[256];

        void append(char c, int xmlOffset) {
            if (sql.length() == offsets.length) {
                int[] grown = new int[offsets.length * 2];
                System.arraycopy(offsets, 0, grown, 0, offsets.length);
                offsets = grown;
            }
            offsets[sql.length()] = xmlOffset;
            sql.append(c);
        }

        /** {@code <include>} の前後の語がつながらないよう、空白を 1 つ入れてから返す。 */
        Output spaced(int xmlOffset) {
            append(' ', xmlOffset);
            return this;
        }

        StaticSql trimmed() {
            int start = 0;
            int end = sql.length();
            while (start < end && Character.isWhitespace(sql.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(sql.charAt(end - 1))) {
                end--;
            }
            int[] kept = new int[end - start];
            System.arraycopy(offsets, start, kept, 0, kept.length);
            return new StaticSql(sql.substring(start, end), kept);
        }
    }
}
