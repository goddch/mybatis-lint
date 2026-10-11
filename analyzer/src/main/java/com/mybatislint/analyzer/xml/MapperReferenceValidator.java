package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java メソッドの情報を必要としない、Mapper XML 内の参照関係を検証する。
 *
 * <p>{@code <include refid="...">}、{@code resultMap/resultType} の同時指定、
 * typeAlias 候補、明示的な resultMap プロパティ、ID 重複、extends・statement の resultMap 参照を扱う。XML とワークスペース内の
 * {@link MapperSqlFragmentIndex}/{@link MapperResultMapIndex}/{@link JavaTypeIndex} で判断できるため、Java Mapper パラメータ
 * 検証とは分けておくと、責務が読み取りやすくなる。</p>
 */
public final class MapperReferenceValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    // MyBatis がデフォルトで登録する組み込み typeAlias(TypeAliasRegistry 由来)。
    // これらは Java クラスとして workspace 内に存在しなくても正当に解決できるため、
    // 誤検知を避けるために「解決済み」として扱う(フェイルオープン)。
    // 大文字/小文字は区別されない(MyBatis 内部でも小文字化して比較している)ため、
    // このセットもすべて小文字で保持する。
    //
    // このリストは mybatis-3 系(org.apache.ibatis.type.TypeAliasRegistry)の
    // デフォルト登録内容をもとに手動で書き起こしたものであり、将来 MyBatis 側で
    // 組み込み alias が追加/削除された場合、このリストは自動追従しない。
    // 実害は「フェイルオープンし損ねて稀に誤検知が増える」程度に留まる設計だが、
    // MyBatis のメジャーバージョンを上げる際はこのリストも見直すことが望ましい。
    private static final Set<String> BUILTIN_TYPE_ALIASES = Set.of(
            "string", "byte", "long", "short", "int", "integer", "double", "float", "boolean",
            "date", "decimal", "bigdecimal", "biginteger", "object", "date[]", "byte[]",
            "map", "hashmap", "list", "arraylist", "collection", "iterator", "iterable",
            "set", "hashset", "linkedhashmap", "linkedhashset", "treemap", "treeset",
            "cursor", "resultset", "char", "character",
            "_byte", "_long", "_short", "_int", "_integer", "_double", "_float", "_boolean", "_char");

    private final MapperSqlFragmentIndex sqlFragmentIndex;
    private final JavaTypeIndex typeIndex;
    private final MapperResultMapIndex resultMapIndex;

    public MapperReferenceValidator(MapperSqlFragmentIndex sqlFragmentIndex) {
        this(sqlFragmentIndex, JavaTypeIndex.empty());
    }

    public MapperReferenceValidator(MapperSqlFragmentIndex sqlFragmentIndex, JavaTypeIndex typeIndex) {
        this(sqlFragmentIndex, typeIndex, MapperResultMapIndex.empty());
    }

    public MapperReferenceValidator(MapperSqlFragmentIndex sqlFragmentIndex, JavaTypeIndex typeIndex,
                                    MapperResultMapIndex resultMapIndex) {
        this.sqlFragmentIndex = sqlFragmentIndex == null ? MapperSqlFragmentIndex.empty() : sqlFragmentIndex;
        this.typeIndex = typeIndex == null ? JavaTypeIndex.empty() : typeIndex;
        this.resultMapIndex = resultMapIndex == null ? MapperResultMapIndex.empty() : resultMapIndex;
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
            validateTypeAliases(mapper, xml, displayFile, uri, diagnostics);
            validateResultProperties(mapper, xml, displayFile, uri, diagnostics);
            validateDuplicateResultMapIds(mapper, namespace, xml, displayFile, uri, diagnostics);
            validateResultMapExtends(mapper, namespace, xml, displayFile, uri, diagnostics);
            validateStatementResultMaps(mapper, namespace, xml, displayFile, uri, diagnostics);
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
        collectDescendantsByTagName(mapper, "include", includes);
        int searchOffset = 0;
        for (Element include : includes) {
            int includeStart = findStartOfElement(xml, include, searchOffset);
            searchOffset = includeStart + "<include".length();
            String refid = include.getAttribute("refid");
            if (refid == null || refid.isBlank()) {
                continue;
            }
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
        collectStatements(mapper, elements);
        int searchOffset = 0;
        for (Element element : elements) {
            int elementStart = findStartOfElement(xml, element, searchOffset);
            searchOffset = elementStart + ("<" + element.getTagName()).length();
            String resultMap = element.getAttribute("resultMap");
            String resultType = element.getAttribute("resultType");
            if (resultMap == null || resultMap.isBlank() || resultType == null || resultType.isBlank()) {
                continue;
            }
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

    private void validateTypeAliases(Element mapper,
                                     String xml,
                                     Path displayFile,
                                     String uri,
                                     List<Diagnostic> diagnostics) {
        // <resultMap type="..."> と、statement の parameterType/resultType の
        // 3 箇所だけを対象にする。スコープを広げすぎると誤検知が増えるため、
        // まずはこの 3 パターンから小さく始める(要件どおりの方針)。
        List<Element> resultMaps = new ArrayList<>();
        collectDescendantsByTagName(mapper, "resultMap", resultMaps);
        int resultMapSearchOffset = 0;
        for (Element resultMap : resultMaps) {
            int elementStart = findStartOfElement(xml, resultMap, resultMapSearchOffset);
            resultMapSearchOffset = elementStart + "<resultMap".length();
            reportIfUnresolvedTypeAlias(resultMap, "type", xml, elementStart, displayFile, uri, diagnostics);
        }

        List<Element> statements = new ArrayList<>();
        collectStatements(mapper, statements);
        int statementSearchOffset = 0;
        for (Element statement : statements) {
            int elementStart = findStartOfElement(xml, statement, statementSearchOffset);
            statementSearchOffset = elementStart + ("<" + statement.getTagName()).length();
            reportIfUnresolvedTypeAlias(statement, "parameterType", xml, elementStart, displayFile, uri, diagnostics);
            reportIfUnresolvedTypeAlias(statement, "resultType", xml, elementStart, displayFile, uri, diagnostics);
        }
    }

    private void reportIfUnresolvedTypeAlias(Element element,
                                             String attributeName,
                                             String xml,
                                             int elementStart,
                                             Path displayFile,
                                             String uri,
                                             List<Diagnostic> diagnostics) {
        String value = element.getAttribute(attributeName);
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        if (isLikelyFullyQualifiedClassName(trimmed)) {
            // "com.example.User" のようにドットを含む値は完全修飾クラス名とみなし、
            // typeAlias の解決対象から外す。FQCN かどうかは Java の
            // classpath を読まないと厳密には判定できないが、mapper XML の
            // 実務上ではドット入りの値はほぼ FQCN(または存在しないなら別の
            // 診断=malformed 相当)なので、ここでは簡易ヒューリスティックで十分。
            return;
        }
        if (value.contains("${")) {
            // ${...} を含む値は実行時のプロパティ置換で決まるため、
            // <include refid> と同じ理由で静的には判断できずフェイルオープンする。
            return;
        }
        if (BUILTIN_TYPE_ALIASES.contains(trimmed.toLowerCase(Locale.ROOT))) {
            // MyBatis 組み込み alias(string, map, list など)は
            // Java クラスとしてワークスペースに存在しなくても正当に解決できる。
            return;
        }
        if (!Character.isUpperCase(trimmed.charAt(0))) {
            // 先頭が小文字の値は、既存の組み込み alias 一覧に載っていなくても、
            // mybatis-config.xml の <typeAliases> やフレームワーク側の設定
            // (例: Spring Boot の mybatis.type-aliases-package)で
            // 小文字 alias として個別登録されている可能性を否定できない。
            // ここで断定的に error を出すと、実際には正しく動いている設定を
            // 誤検知してしまうため、「大文字始まりの DTO/Entity らしい名前」
            // だけを候補として扱うフェイルオープン方針にしている。
            return;
        }
        if (typeIndex.containsSimpleName(trimmed)) {
            // ワークスペース内に同名の class/interface が見つかった。
            // MyBatis はデフォルトで simple name をそのまま alias として使うため、
            // これは解決済みとみなしてよい。
            return;
        }
        diagnostics.add(new Diagnostic(displayFile, uri,
                rangeForAttributeValue(xml, element, attributeName, value, elementStart),
                Severity.WARNING,
                RuleIds.UNRESOLVED_TYPE_ALIAS,
                "Unresolved typeAlias candidate '" + value + "' for attribute '" + attributeName
                        + "'; no matching Java class/interface simple name was found in the workspace."));
    }

    private static boolean isLikelyFullyQualifiedClassName(String value) {
        return value.contains(".");
    }

    private void validateResultProperties(Element mapper,
                                         String xml,
                                         Path displayFile,
                                         String uri,
                                         List<Diagnostic> diagnostics) {
        int searchOffset = 0;
        NodeList mappings = mapper.getElementsByTagName("*");
        for (int i = 0; i < mappings.getLength(); i++) {
            Element mapping = (Element) mappings.item(i);
            if (!"id".equals(mapping.getTagName()) && !"result".equals(mapping.getTagName())) {
                continue;
            }
            int mappingStart = findStartOfElement(xml, mapping, searchOffset);
            searchOffset = mappingStart + ("<" + mapping.getTagName()).length();
            // ネストした mapping も位置検索は進めるが、今回の検証対象には含めない。
            if (!(mapping.getParentNode() instanceof Element resultMap)
                    || !"resultMap".equals(resultMap.getTagName()) || resultMap.getParentNode() != mapper) {
                continue;
            }
            String type = resultMap.getAttribute("type").trim();
            if (!type.contains(".") || type.contains("${") || resultMap.hasAttribute("extends")
                    || resultMap.getElementsByTagName("discriminator").getLength() > 0) {
                continue;
            }
            Set<String> properties = typeIndex.findResultProperties(type);
            if (properties == null) {
                continue;
            }
            String property = mapping.getAttribute("property");
            if (property.isBlank() || property.contains("${") || property.contains(".")
                    || property.contains("[") || property.contains("]") || properties.contains(property)) {
                continue;
            }
            diagnostics.add(new Diagnostic(displayFile, uri,
                    rangeForAttributeValue(xml, mapping, "property", property, mappingStart),
                    Severity.WARNING,
                    RuleIds.UNKNOWN_RESULT_PROPERTY,
                    "Unknown resultMap property '" + property + "' on Java type '" + type
                            + "'; no matching field or setter was found."));
        }
    }

    private void validateDuplicateResultMapIds(Element mapper, String namespace, String xml,
                                               Path displayFile, String uri, List<Diagnostic> diagnostics) {
        if (namespace.isBlank() || namespace.contains("${")) {
            return;
        }
        Map<String, Integer> localCounts = MapperResultMapIndex.collectLocalIdCounts(mapper);
        String prefix = namespace + ".";
        List<Element> resultMaps = new ArrayList<>();
        collectDescendantsByTagName(mapper, "resultMap", resultMaps);
        int searchOffset = 0;
        for (Element resultMap : resultMaps) {
            int elementStart = findStartOfElement(xml, resultMap, searchOffset);
            searchOffset = elementStart + "<resultMap".length();
            String id = resultMap.getAttribute("id");
            if (resultMap.getParentNode() != mapper || id.isBlank() || id.contains("${")) {
                continue;
            }
            String localId = id.startsWith(prefix) ? id.substring(prefix.length()) : id;
            if (localCounts.getOrDefault(localId, 0) > 1 || resultMapIndex.isDuplicate(prefix + localId)) {
                diagnostics.add(new Diagnostic(displayFile, uri,
                        rangeForAttributeValue(xml, resultMap, "id", id, elementStart),
                        Severity.ERROR, RuleIds.DUPLICATE_RESULT_MAP_ID,
                        "Duplicate resultMap id '" + id + "' in namespace '" + namespace + "'."));
            }
        }
    }

    private void validateResultMapExtends(Element mapper, String namespace, String xml,
                                          Path displayFile, String uri, List<Diagnostic> diagnostics) {
        if (namespace.isBlank() || namespace.contains("${")) {
            return;
        }
        Set<String> localIds = MapperResultMapIndex.collectLocalIds(mapper);
        List<Element> resultMaps = new ArrayList<>();
        collectDescendantsByTagName(mapper, "resultMap", resultMaps);
        int searchOffset = 0;
        for (Element resultMap : resultMaps) {
            int elementStart = findStartOfElement(xml, resultMap, searchOffset);
            searchOffset = elementStart + "<resultMap".length();
            if (resultMap.getParentNode() != mapper) {
                continue;
            }
            String parent = resultMap.getAttribute("extends");
            if (parent.isBlank() || parent.contains("${")) {
                continue;
            }
            if (isResultMapResolved(parent, namespace, localIds)) {
                continue;
            }
            diagnostics.add(new Diagnostic(displayFile, uri,
                    rangeForAttributeValue(xml, resultMap, "extends", parent, elementStart),
                    Severity.WARNING, RuleIds.UNRESOLVED_RESULT_MAP_EXTENDS,
                    "Unresolved resultMap extends '" + parent
                            + "'; no matching top-level resultMap was found in the workspace."));
        }
    }

    private void validateStatementResultMaps(Element mapper, String namespace, String xml,
                                             Path displayFile, String uri, List<Diagnostic> diagnostics) {
        if (namespace.isBlank() || namespace.contains("${")) {
            return;
        }
        Set<String> localIds = MapperResultMapIndex.collectLocalIds(mapper);
        List<Element> statements = new ArrayList<>();
        collectStatements(mapper, statements);
        int searchOffset = 0;
        for (Element statement : statements) {
            int elementStart = findStartOfElement(xml, statement, searchOffset);
            searchOffset = elementStart + ("<" + statement.getTagName()).length();
            if (statement.getParentNode() != mapper) {
                continue;
            }
            String value = statement.getAttribute("resultMap");
            if (value.isBlank() || value.contains("${") || value.contains(",")
                    || !statement.getAttribute("resultType").isBlank()) {
                continue;
            }
            if (isResultMapResolved(value.trim(), namespace, localIds)) {
                continue;
            }
            diagnostics.add(new Diagnostic(displayFile, uri,
                    rangeForAttributeValue(xml, statement, "resultMap", value, elementStart),
                    Severity.WARNING, RuleIds.UNRESOLVED_STATEMENT_RESULT_MAP,
                    "Unresolved statement resultMap '" + value
                            + "'; no matching top-level resultMap was found in the workspace."));
        }
    }

    private boolean isResultMapResolved(String id, String namespace, Set<String> localIds) {
        String qualifiedId = id.contains(".") ? id : namespace + "." + id;
        String prefix = namespace + ".";
        return (qualifiedId.startsWith(prefix) && localIds.contains(qualifiedId.substring(prefix.length())))
                || resultMapIndex.contains(qualifiedId);
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

    private static void collectStatements(Element element, List<Element> target) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (STATEMENT_TAGS.contains(child.getTagName())) {
                target.add(child);
            }
            collectStatements(child, target);
        }
    }

    private static void collectDescendantsByTagName(Element element, String tagName, List<Element> target) {
        // 位置計算では「前に処理した同種タグより後ろ」を検索開始位置にする。
        // そのため、不要なタグまで混ざったリストではなく、目的のタグだけを
        // 文書順に集めておくと、同じ include が複数ある場合も診断位置が安定する。
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) {
                continue;
            }
            if (tagName.equals(child.getTagName())) {
                target.add(child);
            }
            collectDescendantsByTagName(child, tagName, target);
        }
    }

    private static Range rangeForAttributeValue(String xml,
                                                Element element,
                                                String attributeName,
                                                String attributeValue,
                                                int elementStart) {
        // 呼び出し側では、同名タグが複数あっても正しい要素を指せるように
        // 文書順の searchOffset を使って開始位置を確定済み。ここで再検索すると
        // 意図が読みにくくなるため、受け取った開始位置から属性値だけを探す。
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
            // 単純な indexOf だけだと、たとえば "id" を探したときに "refid" の
            // 末尾にも一致してしまう。XML 属性名としての境界を確認し、将来この
            // helper を別属性へ再利用しても安全な位置を返すようにしている。
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

    private static int findStartOfElement(String xml, Element element, int lowerBound) {
        String tag = element.getTagName();
        String search = "<" + tag;
        int index = xml.indexOf('<', lowerBound);
        while (index >= 0) {
            // DOM が除外するコメント・CDATA・処理命令や属性値内の文字列は、
            // 実際の開始タグとして位置検索に使わない。
            String terminator = xml.startsWith("<!--", index) ? "-->"
                    : xml.startsWith("<![CDATA[", index) ? "]]>"
                    : xml.startsWith("<?", index) ? "?>" : null;
            if (terminator != null) {
                int end = xml.indexOf(terminator, index);
                if (end < 0) {
                    return lowerBound;
                }
                index = xml.indexOf('<', end + terminator.length());
                continue;
            }
            // "<select" だけで探すと "<selectKey>" にも一致してしまう。
            // タグ名の直後が XML 名文字でないことを確認し、実際のタグ境界だけを
            // 採用することで range が別要素へずれるのを防ぐ。
            int afterName = index + search.length();
            if (index >= lowerBound && xml.startsWith(search, index)
                    && (afterName >= xml.length() || !isXmlNameChar(xml.charAt(afterName)))) {
                return index;
            }
            int end = findStartTagEnd(xml, index);
            if (end < 0) {
                return lowerBound;
            }
            index = xml.indexOf('<', end + 1);
        }
        return lowerBound;
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
        // column は 1-based。最後に見つけた改行の次の文字を 1 桁目として数えると、
        // 「改行直後の文字は 1 列目」という editor 表示と同じ定義になる。
        int column = safeIndex - lastLineStart + 1;
        return new Position(line, column);
    }
}
