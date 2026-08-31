package com.mybatislint.analyzer.xml;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Java メソッドの情報を必要としない、Mapper XML 内の参照関係を検証する。
 *
 * <p>現時点では {@code <include refid="...">} と {@code resultMap/resultType}
 * の同時指定を扱う。どちらも XML 単体、またはワークスペース内の
 * {@link MapperSqlFragmentIndex} だけで判断できるため、Java Mapper パラメータ
 * 検証とは分けておくと、責務が読み取りやすくなる。</p>
 */
public final class MapperReferenceValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private final MapperSqlFragmentIndex sqlFragmentIndex;

    public MapperReferenceValidator(MapperSqlFragmentIndex sqlFragmentIndex) {
        this.sqlFragmentIndex = sqlFragmentIndex == null ? MapperSqlFragmentIndex.empty() : sqlFragmentIndex;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        try {
            String xml = Files.readString(actualFile, StandardCharsets.UTF_8);
            DocumentBuilder builder = SafeXmlParserFactory.newDocumentBuilder();
            Document document = builder.parse(actualFile.toFile());
            Element mapper = document.getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return List.of();
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            String namespace = mapper.getAttribute("namespace");
            Set<String> localSqlIds = collectLocalSqlIds(mapper);
            validateIncludes(mapper, namespace, localSqlIds, xml, displayFile, uri, diagnostics);
            validateResultMapAndType(mapper, xml, displayFile, uri, diagnostics);
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方や読み込み失敗は MapperXmlAnalyzer 側の SAX パースで診断する。
            // ここで二重に報告すると利用者が原因を読みづらくなるので、参照検証は
            // 失敗時に黙って終了するフェイルオープン方針にしている。
            return List.of();
        }
    }

    private void validateIncludes(Element mapper,
                                  String namespace,
                                  Set<String> localSqlIds,
                                  String xml,
                                  Path displayFile,
                                  String uri,
                                  List<Diagnostic> diagnostics) {
        List<Element> includes = new ArrayList<>();
        collectDescendants(mapper, includes);
        int searchOffset = 0;
        for (Element include : includes) {
            if (!"include".equals(include.getTagName())) {
                continue;
            }
            String refid = include.getAttribute("refid");
            if (refid == null || refid.isBlank()) {
                continue;
            }
            int includeStart = findStartOfElement(xml, include, searchOffset);
            searchOffset = includeStart + "<include".length();
            if (isDynamicRefid(refid)) {
                // ${...} を含む refid は MyBatis のプロパティ置換で実行時に変わる。
                // 静的解析で候補を確定できないため、「誤検知を出すくらいなら報告しない」
                // というこのプロジェクトの方針に合わせて診断しない。
                continue;
            }
            if (isResolved(refid, namespace, localSqlIds)) {
                continue;
            }
            diagnostics.add(new Diagnostic(displayFile, uri,
                    rangeForAttributeValue(xml, include, "refid", refid, includeStart),
                    Severity.ERROR,
                    RuleIds.UNRESOLVED_INCLUDE,
                    "Unresolved <include> refid '" + refid + "'."));
        }
    }

    private void validateResultMapAndType(Element mapper,
                                          String xml,
                                          Path displayFile,
                                          String uri,
                                          List<Diagnostic> diagnostics) {
        List<Element> elements = new ArrayList<>();
        collectDescendants(mapper, elements);
        int searchOffset = 0;
        for (Element element : elements) {
            if (!STATEMENT_TAGS.contains(element.getTagName())) {
                continue;
            }
            String resultMap = element.getAttribute("resultMap");
            String resultType = element.getAttribute("resultType");
            if (resultMap == null || resultMap.isBlank() || resultType == null || resultType.isBlank()) {
                continue;
            }
            int elementStart = findStartOfElement(xml, element, searchOffset);
            searchOffset = elementStart + ("<" + element.getTagName()).length();
            // MyBatis の select 系ステートメントでは、結果の変換方法は resultMap か
            // resultType のどちらか一方で表すのが基本。両方を書くと意図が曖昧で、
            // 将来のリファクタリング時にも読み手が迷うため warning として知らせる。
            diagnostics.add(new Diagnostic(displayFile, uri,
                    rangeForAttributeValue(xml, element, "resultMap", resultMap, elementStart),
                    Severity.WARNING,
                    RuleIds.RESULT_MAP_AND_TYPE,
                    "Statement specifies both resultMap and resultType; use only one result mapping strategy."));
        }
    }

    private boolean isResolved(String refid, String namespace, Set<String> localSqlIds) {
        if (!refid.contains(".")) {
            return localSqlIds.contains(refid);
        }
        String currentNamespacePrefix = namespace == null || namespace.isBlank() ? "" : namespace + ".";
        if (!currentNamespacePrefix.isEmpty() && refid.startsWith(currentNamespacePrefix)) {
            String localId = refid.substring(currentNamespacePrefix.length());
            if (localSqlIds.contains(localId)) {
                return true;
            }
        }
        return sqlFragmentIndex.containsQualifiedRefid(refid);
    }

    private static boolean isDynamicRefid(String refid) {
        return refid.contains("${");
    }

    private static Set<String> collectLocalSqlIds(Element mapper) {
        Set<String> ids = new HashSet<>();
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element) || !"sql".equals(element.getTagName())) {
                continue;
            }
            String id = element.getAttribute("id");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static void collectDescendants(Element element, List<Element> target) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            target.add(child);
            collectDescendants(child, target);
        }
    }

    private static Range rangeForAttributeValue(String xml,
                                                Element element,
                                                String attributeName,
                                                String attributeValue,
                                                int lowerBound) {
        int elementStart = findStartOfElement(xml, element, lowerBound);
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
            int cursor = attrIndex + attributeName.length();
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

    private static int findStartOfElement(String xml, Element element, int lowerBound) {
        String tag = element.getTagName();
        String search = "<" + tag;
        int index = xml.indexOf(search, lowerBound);
        return index >= 0 ? index : lowerBound;
    }

    private static Position toPosition(String xml, int index) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < index && i < xml.length(); i++) {
            if (xml.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }
}
