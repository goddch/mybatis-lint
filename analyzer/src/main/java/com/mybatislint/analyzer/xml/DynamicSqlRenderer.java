package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.xml.MapperXmlTree.Element;
import com.mybatislint.analyzer.xml.MapperXmlTree.Node;
import com.mybatislint.analyzer.xml.MapperXmlTree.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * statement を、MyBatis と同じ規則でタグのない SQL に組み立てる。SQL の構文チェックに使う。
 *
 * <p>規則は拡張機能の SQL プレビュー({@code vscode-extension/src/sqlPreview.ts})とそろえている。
 * {@code <where>} / {@code <set>} / {@code <trim>} の上書き、{@code <include>} の展開(別ファイルと
 * {@code <property>})、{@code <foreach>} の open / separator / close。両方のテストで
 * {@code fixtures/sql-render/} の同じ見本を使い、組み立て方がずれないようにしている。</p>
 *
 * <p>プレビューと違い、組み立てた SQL の 1 文字ずつが元の XML のどこから来たかを覚える
 * (構文の誤りを XML の位置で報告するため)。{@code #{...}} は JDBC に渡すときと同じ {@code ?} にする。</p>
 */
final class DynamicSqlRenderer {

    private static final List<String> WHERE_OVERRIDES =
            List.of("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t");
    private static final Pattern PARAMETER = Pattern.compile("([#$])\\{([^}]*)\\}");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]*)\\}");
    // <include> の入れ子がこれより深いものは組み立てない(循環の見落としに備える)。
    private static final int MAX_INCLUDE_DEPTH = 8;

    /**
     * どの分岐を選ぶか。{@code onlyIfs} が null でなければ、そこに入っている {@code <if>} だけを成り立たせる
     * ({@code ifsOn} は使わない)。
     */
    record Variant(boolean ifsOn, boolean lastChooseBranch, int foreachCount, Set<Element> onlyIfs) {
        /** プレビューの初期表示と同じ。{@code <if>} はすべて成り立ち、{@code <choose>} は最初の分岐。 */
        static final Variant ALL_ON = new Variant(true, false, 2, null);
        /** {@code <if>} はすべて成り立たず、{@code <choose>} は最後の分岐。 */
        static final Variant ALL_OFF = new Variant(false, true, 1, null);

        /** {@code ifs} だけを成り立たせ、ほかは {@link #ALL_ON} と同じ。 */
        static Variant only(Set<Element> ifs) {
            return new Variant(false, false, 2, ifs);
        }

        boolean enables(Element ifElement) {
            return onlyIfs == null ? ifsOn : onlyIfs.contains(ifElement);
        }
    }

    /**
     * 組み立てた SQL。{@code hasDollar} は値がそのまま埋め込まれる {@code ${...}} が残ったかどうか
     * (残ると SQL が値しだいで変わるので、構文チェックはしない)。
     */
    record Rendered(String sql, int[] xmlOffsets, boolean hasDollar, boolean complete) {
        int xmlOffset(int sqlIndex) {
            if (xmlOffsets.length == 0) {
                return 0;
            }
            return xmlOffsets[Math.max(0, Math.min(sqlIndex, xmlOffsets.length - 1))];
        }
    }

    /** {@code <include>} の先の {@code <sql>} を namespace と id で探す。見つからなければ null。 */
    interface FragmentLookup extends BiFunction<String, String, Element> {
    }

    private final FragmentLookup fragments;

    DynamicSqlRenderer(FragmentLookup fragments) {
        this.fragments = fragments;
    }

    Rendered render(Element statement, String namespace, Variant variant) {
        Context context = new Context(namespace, variant, Map.of(), List.of());
        Output out = new Output();
        renderChildren(statement, context, out);
        return out.build(context.state);
    }

    private void renderChildren(Element element, Context context, Output out) {
        for (Node child : element.children()) {
            renderNode(child, context, out);
        }
    }

    private void renderNode(Node node, Context context, Output out) {
        if (node instanceof Text text) {
            renderText(text, context, out);
            return;
        }
        Element element = (Element) node;
        switch (element.name()) {
            case "if" -> {
                if (context.variant.enables(element)) {
                    renderChildren(element, context, out);
                }
            }
            case "choose" -> {
                List<Element> branches = new ArrayList<>();
                for (Element branch : element.childElements()) {
                    if (branch.name().equals("when") || branch.name().equals("otherwise")) {
                        branches.add(branch);
                    }
                }
                if (!branches.isEmpty()) {
                    renderChildren(branches.get(context.variant.lastChooseBranch() ? branches.size() - 1 : 0),
                            context, out);
                }
            }
            case "foreach" -> renderForeach(element, context, out);
            case "where" -> trim(element, context, out, "WHERE", WHERE_OVERRIDES, "", List.of());
            case "set" -> trim(element, context, out, "SET", List.of(","), "", List.of(","));
            case "trim" -> trim(element, context, out, element.attribute("prefix"),
                    overrides(element.attribute("prefixOverrides")), element.attribute("suffix"),
                    overrides(element.attribute("suffixOverrides")));
            case "include" -> renderInclude(element, context, out);
            case "bind", "selectKey", "property" -> {
                // <bind> は変数を作るだけ、<selectKey> は別の SQL として実行される。
            }
            default -> renderChildren(element, context, out);
        }
    }

    private void renderForeach(Element element, Context context, Output out) {
        int at = element.start();
        out.append(" ", at);
        out.append(element.attribute("open"), at);
        for (int i = 0; i < context.variant.foreachCount(); i++) {
            if (i > 0) {
                out.append(element.attribute("separator"), at);
            }
            renderChildren(element, context, out);
        }
        // MyBatis と同じく、要素が 0 個でも open と close は書かれる。
        out.append(element.attribute("close"), at);
        out.append(" ", at);
    }

    private void renderInclude(Element element, Context context, Output out) {
        String refid = substituteProperties(element.attribute("refid"), context.properties);
        int dot = refid.lastIndexOf('.');
        String namespace = dot < 0 ? context.namespace : refid.substring(0, dot);
        String id = dot < 0 ? refid : refid.substring(dot + 1);
        String qualified = namespace + "." + id;
        Element fragment = context.including.contains(qualified) || context.including.size() >= MAX_INCLUDE_DEPTH
                ? null
                : fragments.apply(namespace, id);
        if (fragment == null) {
            // 組み立てきれない SQL は、誤りがなくても読めないことがあるのでチェックしない。
            context.state.complete = false;
            return;
        }
        Map<String, String> properties = new HashMap<>(context.properties);
        for (Element property : element.childElements()) {
            if (property.name().equals("property")) {
                properties.put(property.attribute("name"),
                        substituteProperties(property.attribute("value"), context.properties));
            }
        }
        List<String> including = new ArrayList<>(context.including);
        including.add(qualified);
        // プレビューと同じく、前後に空白は足さない(MyBatis も足さない)。
        renderChildren(fragment, new Context(namespace, context.variant, properties, including, context.state), out);
    }

    /** {@code <include>} の {@code <property>} の値で、断片の中の {@code ${name}} を置き換える。 */
    private static String substituteProperties(String text, Map<String, String> properties) {
        Matcher matcher = PROPERTY.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = properties.get(matcher.group(1).trim());
            matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void renderText(Text text, Context context, Output out) {
        String value = text.text();
        Matcher matcher = PARAMETER.matcher(value);
        int i = 0;
        while (matcher.find()) {
            for (int k = i; k < matcher.start(); k++) {
                out.append(value.charAt(k), text.offsets()[k]);
            }
            int at = text.offsets()[matcher.start()];
            if (matcher.group(1).equals("#")) {
                out.append('?', at);
            } else {
                String property = context.including.isEmpty() ? null : context.properties.get(matcher.group(2).trim());
                if (property != null) {
                    out.append(property, at);
                } else {
                    context.state.hasDollar = true;
                    out.append(matcher.group(), at);
                }
            }
            i = matcher.end();
        }
        for (int k = i; k < value.length(); k++) {
            out.append(value.charAt(k), text.offsets()[k]);
        }
    }

    /**
     * MyBatis の TrimSqlNode と同じ。中身の前後の空白を除き、合う上書き(prefixOverrides)を 1 つだけ外してから、
     * 空でなければ prefix / suffix で囲む。足した prefix / suffix は要素の位置を指す。
     */
    private void trim(Element element, Context context, Output out, String prefix, List<String> prefixOverrides,
                      String suffix, List<String> suffixOverrides) {
        Output content = new Output();
        renderChildren(element, context, content);
        content.strip();
        int at = element.start();
        if (content.length() == 0) {
            out.append(" ", at);
            return;
        }
        String upper = content.toString().toUpperCase(Locale.ROOT);
        for (String override : prefixOverrides) {
            if (upper.startsWith(override.toUpperCase(Locale.ROOT))) {
                content.removeFirst(override.length());
                break;
            }
        }
        String afterPrefix = content.toString().toUpperCase(Locale.ROOT);
        for (String override : suffixOverrides) {
            if (afterPrefix.endsWith(override.toUpperCase(Locale.ROOT))) {
                content.removeLast(override.length());
                break;
            }
        }
        out.append(" ", at);
        if (!prefix.isEmpty()) {
            out.append(prefix + " ", at);
        }
        out.append(content);
        if (!suffix.isEmpty()) {
            out.append(" " + suffix, at);
        }
        out.append(" ", at);
    }

    private static List<String> overrides(String attribute) {
        return attribute.isEmpty() ? List.of() : List.of(attribute.split("\\|"));
    }

    /** 組み立て全体で共有する状態。 */
    private static final class State {
        boolean hasDollar;
        boolean complete = true;
    }

    private static final class Context {
        final String namespace;
        final Variant variant;
        final Map<String, String> properties;
        final List<String> including;
        final State state;

        Context(String namespace, Variant variant, Map<String, String> properties, List<String> including) {
            this(namespace, variant, properties, including, new State());
        }

        Context(String namespace, Variant variant, Map<String, String> properties, List<String> including,
                State state) {
            this.namespace = namespace;
            this.variant = variant;
            this.properties = properties;
            this.including = including;
            this.state = state;
        }
    }

    /** 組み立て中の SQL と、各文字の XML での位置。 */
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

        void append(String text, int xmlOffset) {
            for (int i = 0; i < text.length(); i++) {
                append(text.charAt(i), xmlOffset);
            }
        }

        void append(Output other) {
            for (int i = 0; i < other.sql.length(); i++) {
                append(other.sql.charAt(i), other.offsets[i]);
            }
        }

        int length() {
            return sql.length();
        }

        void strip() {
            int end = sql.length();
            while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
                end--;
            }
            sql.setLength(end);
            int start = 0;
            while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
                start++;
            }
            removeFirst(start);
        }

        void removeFirst(int count) {
            System.arraycopy(offsets, count, offsets, 0, sql.length() - count);
            sql.delete(0, count);
        }

        void removeLast(int count) {
            sql.setLength(sql.length() - count);
        }

        @Override
        public String toString() {
            return sql.toString();
        }

        Rendered build(State state) {
            Output copy = new Output();
            copy.append(this);
            copy.strip();
            int[] kept = new int[copy.sql.length()];
            System.arraycopy(copy.offsets, 0, kept, 0, kept.length);
            return new Rendered(copy.sql.toString(), kept, state.hasDollar, state.complete);
        }
    }
}
