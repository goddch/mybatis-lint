package com.mybatislint.analyzer.xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mapper XML を、テキストの 1 文字ずつが元のファイルのどこにあったかを覚えたまま読む。
 *
 * <p>SQL の構文チェックで、組み立てた SQL の誤りを元の XML の位置で報告するために使う。
 * DOM では元の位置がわからないので、拡張機能の SQL プレビュー({@code mapperXml.ts})と同じ読み方を
 * Java で行う。両方が同じ SQL を組み立てられるよう、読み方(コメントは捨てる、CDATA はテキスト、
 * 文字参照は元の文字にする)をそろえている。</p>
 */
final class MapperXmlTree {

    private static final Pattern NAME = Pattern.compile("^[\\w:.-]+");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:.-]+)\\s*=\\s*([\"'])([\\s\\S]*?)\\2");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|\\w+);");

    sealed interface Node permits Element, Text {
    }

    /**
     * 要素。{@code start} は開始タグの {@code <} の位置、{@code end} は終了タグ(自己終了なら開始タグ)の直後。
     * 同じ内容の要素でも別のものとして扱う(分岐を選ぶときに要素そのものを目印にするため)。
     */
    static final class Element implements Node {
        private final String name;
        private final Map<String, String> attributes;
        private final List<Node> children;
        private final int start;
        private int end;

        Element(String name, Map<String, String> attributes, List<Node> children, int start, int end) {
            this.name = name;
            this.attributes = attributes;
            this.children = children;
            this.start = start;
            this.end = end;
        }

        String name() {
            return name;
        }

        Map<String, String> attributes() {
            return attributes;
        }

        List<Node> children() {
            return children;
        }

        int start() {
            return start;
        }

        int end() {
            return end;
        }

        /** XML の位置 {@code offset} が、この要素の中にあるか。 */
        boolean contains(int offset) {
            return start <= offset && offset < end;
        }

        String attribute(String attributeName) {
            return attributes.getOrDefault(attributeName, "");
        }

        List<Element> childElements() {
            List<Element> elements = new ArrayList<>();
            for (Node child : children) {
                if (child instanceof Element element) {
                    elements.add(element);
                }
            }
            return elements;
        }
    }

    /** テキスト。{@code offsets[i]} は {@code text} の i 文字目の、元のファイルでの位置。 */
    record Text(String text, int[] offsets) implements Node {
    }

    private MapperXmlTree() {
    }

    /** 文書のルート要素。なければ null。 */
    static Element parse(String xml) {
        Element document = new Element("#document", Map.of(), new ArrayList<>(), 0, xml.length());
        List<Element> stack = new ArrayList<>();
        stack.add(document);
        List<TextBuilder> openText = new ArrayList<>();
        openText.add(null);
        int i = 0;
        while (i < xml.length()) {
            int open = xml.indexOf('<', i);
            int textEnd = open < 0 ? xml.length() : open;
            if (textEnd > i) {
                appendDecoded(xml, i, textEnd, text(stack, openText));
            }
            if (open < 0) {
                break;
            }
            if (xml.startsWith("<!--", open)) {
                int close = xml.indexOf("-->", open + 4);
                i = close < 0 ? xml.length() : close + 3;
            } else if (xml.startsWith("<![CDATA[", open)) {
                int close = xml.indexOf("]]>", open + 9);
                int end = close < 0 ? xml.length() : close;
                TextBuilder target = text(stack, openText);
                for (int k = open + 9; k < end; k++) {
                    target.append(xml.charAt(k), k);
                }
                i = close < 0 ? xml.length() : close + 3;
            } else if (xml.startsWith("<?", open) || xml.startsWith("<!", open)) {
                int close = tagEnd(xml, open);
                i = close < 0 ? xml.length() : close + 1;
            } else if (xml.startsWith("</", open)) {
                int close = tagEnd(xml, open);
                String name = xml.substring(open + 2, close < 0 ? xml.length() : close).trim();
                int index = -1;
                for (int k = stack.size() - 1; k > 0; k--) {
                    if (stack.get(k).name().equals(name)) {
                        index = k;
                        break;
                    }
                }
                if (index > 0) {
                    int end = close < 0 ? xml.length() : close + 1;
                    while (stack.size() > index) {
                        stack.get(stack.size() - 1).end = end;
                        closeTop(stack, openText);
                    }
                }
                i = close < 0 ? xml.length() : close + 1;
            } else {
                int close = tagEnd(xml, open);
                String inside = xml.substring(open + 1, close < 0 ? xml.length() : close);
                Matcher name = NAME.matcher(inside);
                if (!name.find()) {
                    text(stack, openText).append('<', open);
                    i = open + 1;
                    continue;
                }
                flushText(stack, openText);
                Element element = new Element(name.group(), parseAttributes(inside.substring(name.end())),
                        new ArrayList<>(), open, close < 0 ? xml.length() : close + 1);
                stack.get(stack.size() - 1).children().add(element);
                if (!inside.endsWith("/")) {
                    stack.add(element);
                    openText.add(null);
                }
                i = close < 0 ? xml.length() : close + 1;
            }
        }
        // 閉じていない要素は、ファイルの終わりで閉じる(編集中のファイルでもプレビューと同じに読む)。
        while (stack.size() > 1) {
            stack.get(stack.size() - 1).end = xml.length();
            closeTop(stack, openText);
        }
        flushText(stack, openText);
        for (Node node : document.children()) {
            if (node instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    /** 開始位置 {@code open} のタグを閉じる {@code >}。属性値の中の {@code >} は数えない。 */
    private static int tagEnd(String xml, int open) {
        char quote = 0;
        for (int i = open + 1; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> parseAttributes(String text) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            TextBuilder value = new TextBuilder();
            appendDecoded(matcher.group(3), 0, matcher.group(3).length(), value);
            attributes.put(matcher.group(1), value.text.toString());
        }
        return attributes;
    }

    /** 文字参照を元の文字にして足す。参照から生まれた文字は {@code &} の位置を指す。 */
    private static void appendDecoded(String source, int start, int end, TextBuilder target) {
        Matcher entity = ENTITY.matcher(source).region(start, end);
        int i = start;
        while (entity.find()) {
            for (int k = i; k < entity.start(); k++) {
                target.append(source.charAt(k), k);
            }
            String decoded = decode(entity.group(1));
            if (decoded == null) {
                for (int k = entity.start(); k < entity.end(); k++) {
                    target.append(source.charAt(k), k);
                }
            } else {
                for (int k = 0; k < decoded.length(); k++) {
                    target.append(decoded.charAt(k), entity.start());
                }
            }
            i = entity.end();
        }
        for (int k = i; k < end; k++) {
            target.append(source.charAt(k), k);
        }
    }

    private static String decode(String name) {
        try {
            if (name.startsWith("#x")) {
                return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
            }
            if (name.startsWith("#")) {
                return new String(Character.toChars(Integer.parseInt(name.substring(1))));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return switch (name) {
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> null;
        };
    }

    /** 今の要素に続けて足すテキスト。直前がテキストならそれにつなげる(プレビューと同じ)。 */
    private static TextBuilder text(List<Element> stack, List<TextBuilder> openText) {
        int top = openText.size() - 1;
        if (openText.get(top) == null) {
            openText.set(top, new TextBuilder());
        }
        return openText.get(top);
    }

    private static void flushText(List<Element> stack, List<TextBuilder> openText) {
        int top = openText.size() - 1;
        TextBuilder builder = openText.get(top);
        if (builder != null) {
            stack.get(top).children().add(builder.build());
            openText.set(top, null);
        }
    }

    private static void closeTop(List<Element> stack, List<TextBuilder> openText) {
        flushText(stack, openText);
        stack.remove(stack.size() - 1);
        openText.remove(openText.size() - 1);
    }

    private static final class TextBuilder {
        private final StringBuilder text = new StringBuilder();
        private int[] offsets = new int[64];

        void append(char c, int offset) {
            if (text.length() == offsets.length) {
                int[] grown = new int[offsets.length * 2];
                System.arraycopy(offsets, 0, grown, 0, offsets.length);
                offsets = grown;
            }
            offsets[text.length()] = offset;
            text.append(c);
        }

        Text build() {
            int[] kept = new int[text.length()];
            System.arraycopy(offsets, 0, kept, 0, kept.length);
            return new Text(text.toString(), kept);
        }
    }
}
