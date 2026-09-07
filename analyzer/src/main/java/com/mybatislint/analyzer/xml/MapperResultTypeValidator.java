package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperMethodInfo;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Position;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code <select resultType="...">} と、一意に解決できた Java Mapper メソッドの
 * 戻り値型を突き合わせる検証器({@code mybatis-lint/result-type-mismatch})。
 *
 * <h2>なぜこれだけ狭いスコープにしたか</h2>
 *
 * <p>{@code parameterType} 側({@link MapperParameterTypeValidator})と違い、
 * resultType は {@code List<T>}/{@code Map}/{@code Cursor}/nested generics など
 * MyBatis 特有の表現の幅が広く、誤検知のリスクが高い。今回はクレジット節約も兼ねて、
 * 「Java 側の戻り値が単一オブジェクト型、または要素がシンプルな
 * {@code List<T>}」という最も安全に断定できるケースだけに絞り、それ以外は
 * すべてフェイルオープンする。{@link MapperParameterTypeValidator} と設計は似て
 * いるが、比較対象(引数 vs 戻り値)も判定できる範囲も異なるため、無理に共通化は
 * していない。</p>
 */
public final class MapperResultTypeValidator {

    private static final Map<String, Set<String>> BUILTIN_ALIAS_JAVA_TYPES = Map.ofEntries(
            Map.entry("string", Set.of("String")),
            Map.entry("byte", Set.of("byte", "Byte")),
            Map.entry("_byte", Set.of("byte", "Byte")),
            Map.entry("long", Set.of("long", "Long")),
            Map.entry("_long", Set.of("long", "Long")),
            Map.entry("short", Set.of("short", "Short")),
            Map.entry("_short", Set.of("short", "Short")),
            Map.entry("int", Set.of("int", "Integer")),
            Map.entry("_int", Set.of("int", "Integer")),
            Map.entry("integer", Set.of("int", "Integer")),
            Map.entry("_integer", Set.of("int", "Integer")),
            Map.entry("double", Set.of("double", "Double")),
            Map.entry("_double", Set.of("double", "Double")),
            Map.entry("float", Set.of("float", "Float")),
            Map.entry("_float", Set.of("float", "Float")),
            Map.entry("boolean", Set.of("boolean", "Boolean")),
            Map.entry("_boolean", Set.of("boolean", "Boolean")),
            Map.entry("char", Set.of("char", "Character")),
            Map.entry("_char", Set.of("char", "Character")),
            Map.entry("character", Set.of("char", "Character")),
            Map.entry("date", Set.of("Date")),
            Map.entry("decimal", Set.of("BigDecimal")),
            Map.entry("bigdecimal", Set.of("BigDecimal")),
            Map.entry("biginteger", Set.of("BigInteger")));

    private final JavaMapperIndex index;

    public MapperResultTypeValidator(JavaMapperIndex index) {
        this.index = index == null ? JavaMapperIndex.empty() : index;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        if (index.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String xml = Files.readString(actualFile, StandardCharsets.UTF_8);
            DocumentBuilder builder = SafeXmlParserFactory.newDocumentBuilder();
            Document document = builder.parse(actualFile.toFile());
            Element mapper = document.getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            String namespace = mapper.getAttribute("namespace");
            MapperInterfaceInfo mapperInterface = index.findInterface(namespace);
            if (mapperInterface == null) {
                // parameterType 側と同じ理由でフェイルオープン: 対応する Java
                // Mapper が見つからないことと、不一致があることは別の意味を持つ。
                return Collections.emptyList();
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            int searchOffset = 0;
            for (Element select : collectSelectStatements(mapper)) {
                int elementStart = findStartOfElement(xml, select, searchOffset);
                searchOffset = elementStart + "<select".length();

                // resultMap を持つ statement は対象外(要件どおり)。
                String resultMap = select.getAttribute("resultMap");
                if (resultMap != null && !resultMap.isBlank()) {
                    continue;
                }

                String resultType = select.getAttribute("resultType");
                if (resultType == null || resultType.isBlank()) {
                    continue;
                }
                String trimmedXmlType = resultType.trim();
                if (trimmedXmlType.contains("${")) {
                    // typeAlias/parameterType 検証と同じ理由: 実行時のプロパティ
                    // 置換で値が変わるため静的には判断できない。
                    continue;
                }

                String methodId = select.getAttribute("id");
                if (methodId == null || methodId.isBlank()) {
                    continue;
                }
                // オーバーロードなどで一意に決まらない場合は null。曖昧な状態から
                // 決め打ちすると誤検知の原因になるためスキップする。
                MapperMethodInfo method = mapperInterface.resolveMethod(methodId);
                if (method == null) {
                    continue;
                }

                ReturnShape javaShape = ReturnShape.parse(method.getReturnType());
                if (javaShape == null) {
                    // void/Map/Cursor/nested generics/array/型変数など、安全に
                    // 断定できない戻り値型はすべてフェイルオープンする。
                    continue;
                }

                if (!matches(trimmedXmlType, javaShape)) {
                    diagnostics.add(new Diagnostic(displayFile, uri,
                            rangeForAttributeValue(xml, select, "resultType", resultType, elementStart),
                            Severity.WARNING,
                            RuleIds.RESULT_TYPE_MISMATCH,
                            "Statement resultType '" + resultType + "' does not match the return type '"
                                    + method.getReturnType() + "' of mapper method '" + method.getName() + "'."));
                }
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方自体は MapperXmlAnalyzer の SAX パースが既に診断済みのはず。
            return Collections.emptyList();
        }
    }

    /**
     * XML 側 resultType の文字列を、Java 側の戻り値の要素 simple name と比較する。
     * 既知の built-in alias は対応する Java 型と比較し、未知の小文字 alias は
     * 個別登録の可能性を否定できないためフェイルオープン。
     */
    private static boolean matches(String xmlValue, ReturnShape javaShape) {
        Set<String> aliasTypes = BUILTIN_ALIAS_JAVA_TYPES.get(xmlValue.toLowerCase(Locale.ROOT));
        if (aliasTypes != null) {
            return aliasTypes.contains(javaShape.elementSimpleName);
        }
        if (!Character.isUpperCase(xmlValue.charAt(0))) {
            return true;
        }
        String xmlSimpleName = xmlValue.contains(".")
                ? xmlValue.substring(xmlValue.lastIndexOf('.') + 1)
                : xmlValue;
        return xmlSimpleName.equals(javaShape.elementSimpleName);
    }

    /** Java 側の戻り値型のうち、今回比較対象にする「単一オブジェクト or 単純な List<T>」の形。 */
    private static final class ReturnShape {
        private final String elementSimpleName;

        private ReturnShape(String elementSimpleName) {
            this.elementSimpleName = elementSimpleName;
        }

        static ReturnShape parse(String rawType) {
            if (rawType == null || rawType.isBlank()) {
                return null;
            }
            String type = rawType.trim();
            if (type.equals("void") || type.endsWith("[]") || type.contains("?")) {
                return null;
            }
            int genericStart = type.indexOf('<');
            if (genericStart < 0) {
                return elementShape(type);
            }
            if (!type.endsWith(">")) {
                return null;
            }
            String outer = type.substring(0, genericStart).trim();
            if (!"List".equals(simpleNameOf(outer))) {
                // Map<K,V>/Cursor<T>/Set<T> など、List 以外のジェネリクスは対象外。
                return null;
            }
            String inner = type.substring(genericStart + 1, type.length() - 1).trim();
            if (inner.isEmpty() || inner.contains("<") || inner.contains(">") || inner.contains(",")) {
                // nested generics や複数型引数は対象外。
                return null;
            }
            return elementShape(inner);
        }

        private static ReturnShape elementShape(String type) {
            String simple = simpleNameOf(type);
            if (simple.isEmpty() || "Map".equals(simple) || "Cursor".equals(simple)) {
                return null;
            }
            if (looksLikeGenericTypeVariable(simple)) {
                return null;
            }
            return new ReturnShape(simple);
        }

        private static String simpleNameOf(String type) {
            return type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        }

        private static boolean looksLikeGenericTypeVariable(String simpleName) {
            if (simpleName.length() > 2) {
                return false;
            }
            for (int i = 0; i < simpleName.length(); i++) {
                if (!Character.isUpperCase(simpleName.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static List<Element> collectSelectStatements(Element mapper) {
        List<Element> selects = new ArrayList<>();
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if ("select".equals(element.getTagName())) {
                selects.add(element);
                continue;
            }
            selects.addAll(collectSelectStatements(element));
        }
        return selects;
    }

    private static int findStartOfElement(String xml, Element element, int lowerBound) {
        String tag = element.getTagName();
        String search = "<" + tag;
        int index = xml.indexOf(search, lowerBound);
        while (index >= 0) {
            int afterName = index + search.length();
            if (afterName >= xml.length() || !isXmlNameChar(xml.charAt(afterName))) {
                return index;
            }
            index = xml.indexOf(search, afterName);
        }
        return lowerBound;
    }

    private static Range rangeForAttributeValue(String xml,
                                                Element element,
                                                String attributeName,
                                                String attributeValue,
                                                int elementStart) {
        int valueStart = findAttributeValueStart(xml, elementStart, attributeName, attributeValue);
        if (valueStart < 0) {
            valueStart = elementStart;
        }
        Position position = toPosition(xml, valueStart);
        return Range.at(position.getLine(), position.getCharacter());
    }

    private static int findAttributeValueStart(String xml, int elementStart, String attributeName, String attributeValue) {
        int tagEnd = findStartTagEnd(xml, elementStart);
        if (tagEnd < 0) {
            return -1;
        }
        String startTag = xml.substring(elementStart, tagEnd + 1);
        int attrIndex = startTag.indexOf(attributeName);
        while (attrIndex >= 0) {
            if (!isAttributeNameBoundaryBefore(startTag, attrIndex)) {
                attrIndex = startTag.indexOf(attributeName, attrIndex + attributeName.length());
                continue;
            }
            int cursor = attrIndex + attributeName.length();
            if (cursor < startTag.length() && isXmlNameChar(startTag.charAt(cursor))) {
                attrIndex = startTag.indexOf(attributeName, attrIndex + attributeName.length());
                continue;
            }
            while (cursor < startTag.length() && Character.isWhitespace(startTag.charAt(cursor))) {
                cursor++;
            }
            if (cursor < startTag.length() && startTag.charAt(cursor) == '=') {
                cursor++;
                while (cursor < startTag.length() && Character.isWhitespace(startTag.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < startTag.length() && (startTag.charAt(cursor) == '"' || startTag.charAt(cursor) == '\'')) {
                    char quote = startTag.charAt(cursor);
                    int valueStartInTag = cursor + 1;
                    int valueEndInTag = startTag.indexOf(quote, valueStartInTag);
                    if (valueEndInTag >= 0 && startTag.substring(valueStartInTag, valueEndInTag).equals(attributeValue)) {
                        return elementStart + valueStartInTag;
                    }
                }
            }
            attrIndex = startTag.indexOf(attributeName, attrIndex + attributeName.length());
        }
        return -1;
    }

    private static boolean isAttributeNameBoundaryBefore(String startTag, int attrIndex) {
        if (attrIndex <= 0) {
            return true;
        }
        return !isXmlNameChar(startTag.charAt(attrIndex - 1));
    }

    private static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
    }

    private static int findStartTagEnd(String xml, int elementStart) {
        char quote = 0;
        for (int i = elementStart; i < xml.length(); i++) {
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

    private static Position toPosition(String xml, int index) {
        int safeIndex = Math.max(0, Math.min(index, xml.length()));
        int line = 1;
        int lastLineStart = 0;
        for (int i = 0; i < safeIndex; i++) {
            if (xml.charAt(i) == '\n') {
                line++;
                lastLineStart = i + 1;
            }
        }
        int column = safeIndex - lastLineStart + 1;
        return new Position(line, column);
    }
}
