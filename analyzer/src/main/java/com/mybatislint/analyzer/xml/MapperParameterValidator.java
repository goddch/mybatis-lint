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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates root-level MyBatis parameter references against Java mapper method
 * signatures when a mapper namespace can be resolved to a Java interface.
 */
public final class MapperParameterValidator {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("#\\{([^}]*)\\}|\\$\\{([^}]*)\\}");
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private final JavaMapperIndex index;

    public MapperParameterValidator(JavaMapperIndex index) {
        this.index = index;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        if (index == null || index.isEmpty()) {
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
                return Collections.emptyList();
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            for (Element statement : collectStatements(mapper)) {
                String methodId = statement.getAttribute("id");
                if (methodId == null || methodId.isBlank()) {
                    continue;
                }
                MapperMethodInfo method = mapperInterface.resolveMethod(methodId);
                if (method == null || method.hasMapLikeParameter()) {
                    continue;
                }
                Set<String> allowedNames = method.availableNames();
                Set<String> statementScope = new LinkedHashSet<>(allowedNames);
                addScopedNames(statement, statementScope);
                validateIfAndWhenTests(statement, statementScope, method, xml, displayFile, uri, diagnostics);
                validateTemplateReferences(statement, statementScope, method, xml, displayFile, uri, diagnostics);
                validateForeachCollections(statement, statementScope, method, xml, displayFile, uri, diagnostics);
            }
            return diagnostics;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void validateTemplateReferences(Element statement,
                                                  Set<String> allowedNames,
                                                  MapperMethodInfo method,
                                                  String xml,
                                                  Path displayFile,
                                                  String uri,
                                                  List<Diagnostic> diagnostics) {
        String sqlText = statement.getTextContent();
        Matcher matcher = TEMPLATE_PATTERN.matcher(sqlText);
        int statementOffset = findStartOfElement(xml, statement, 0);
        int searchOffset = statementOffset;
        while (matcher.find()) {
            String raw = matcher.group();
            String expression = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (expression == null) {
                continue;
            }
            for (String root : ExpressionRootIdentifierExtractor.extractRoots(expression)) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                if (allowedNames.contains(root)) {
                    continue;
                }
                if (root.startsWith("_")) {
                    continue;
                }
                diagnostics.add(diagnosticForExpression(xml, displayFile, uri, method, root,
                        raw, searchOffset));
            }
            int nextIndex = xml.indexOf(raw, searchOffset);
            if (nextIndex >= 0) {
                searchOffset = nextIndex + raw.length();
            }
        }
    }

    private static void validateIfAndWhenTests(Element statement,
                                               Set<String> allowedNames,
                                               MapperMethodInfo method,
                                               String xml,
                                               Path displayFile,
                                               String uri,
                                               List<Diagnostic> diagnostics) {
        int lowerBound = findStartOfElement(xml, statement, 0);
        List<Element> elements = new ArrayList<>();
        collectDescendants(statement, elements);
        for (Element element : elements) {
            String tagName = element.getTagName();
            if (!"if".equals(tagName) && !"when".equals(tagName)) {
                continue;
            }
            String expression = element.getAttribute("test");
            if (expression == null || expression.isBlank()) {
                continue;
            }
            for (String root : ExpressionRootIdentifierExtractor.extractRoots(expression)) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                if (allowedNames.contains(root)) {
                    continue;
                }
                if (root.startsWith("_")) {
                    continue;
                }
                diagnostics.add(diagnosticForExpression(xml, displayFile, uri, method, root,
                        expression, lowerBound));
            }
        }
    }

    /**
     * {@code <foreach collection="...">} の collection 属性を検証する。
     *
     * なぜ collection 属性を見るのか:
     *   MyBatis は collection 属性を「実行時パラメータオブジェクトに対する OGNL 式」として
     *   解決する。よく使われるのは単純な識別子(例: "list", "array", パラメータ名そのもの)だが、
     *   "request.items" のようにプロパティを辿るケースもある。#{...} や test 属性と同様に、
     *   ここでも「ルート識別子」だけを見れば、mapper メソッドの引数として存在しない名前を
     *   参照している(タイプミス・リファクタリング漏れ等)ケースを保守的に検出できる。
     *
     * スコープ変数(bind/foreach の item・index)の扱いについて:
     *   本来 MyBatis の OGNL 評価は入れ子構造に従うため、ある <foreach> の item/index は
     *   その <foreach> 配下でしか参照できず、自分自身の collection 属性の評価時にはまだ
     *   定義されていない。しかし本アナライザーは既存の addScopedNames と同じく、
     *   ステートメント全体で bind/foreach の変数名をフラットに集約したスコープ
     *   (statementScope)を使う設計にしている。厳密なネスト解析は複雑になり誤検知の
     *   リスクが増すため、「多少緩くても false positive を出さない」ことを優先した
     *   フェイルオープン戦略の一部である。
     */
    private static void validateForeachCollections(Element statement,
                                                    Set<String> allowedNames,
                                                    MapperMethodInfo method,
                                                    String xml,
                                                    Path displayFile,
                                                    String uri,
                                                    List<Diagnostic> diagnostics) {
        int statementStart = findStartOfElement(xml, statement, 0);
        List<Element> elements = new ArrayList<>();
        collectDescendants(statement, elements);
        // 同じステートメント内に <foreach collection="ids"> が複数回登場するケース
        // (例: 複数の IN 句を持つバッチ SQL)では、常に statementStart から検索すると
        // 常に最初の一致に位置が引っ張られてしまう。そのため要素ごとの開始位置を
        // 求め、search 済みの範囲より後ろだけを検索対象にすることで、診断位置が
        // 実際にタイプミスがある <foreach> に近づくようにしている。
        int searchOffset = statementStart;
        for (Element element : elements) {
            if (!"foreach".equals(element.getTagName())) {
                continue;
            }
            String expression = element.getAttribute("collection");
            if (expression == null || expression.isBlank()) {
                continue;
            }
            int elementStart = findStartOfElement(xml, element, searchOffset);
            for (String root : ExpressionRootIdentifierExtractor.extractRoots(expression)) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                if (allowedNames.contains(root)) {
                    continue;
                }
                // _parameter や _databaseId など、MyBatis が暗黙に提供する組み込み変数は
                // "_" で始まる名前として扱われる。既存の #{...} / test 属性検証と同じ
                // ルールで許可し、誤検知を避ける。
                if (root.startsWith("_")) {
                    continue;
                }
                diagnostics.add(diagnosticForExpression(xml, displayFile, uri, method, root,
                        expression, elementStart));
            }
            searchOffset = elementStart + "<foreach".length();
        }
    }

    private static void addScopedNames(Element element, Set<String> inScope) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node childNode = children.item(i);
            if (!(childNode instanceof Element child)) {
                continue;
            }
            String tag = child.getTagName();
            if ("bind".equals(tag)) {
                String name = child.getAttribute("name");
                if (name != null && !name.isBlank()) {
                    inScope.add(name);
                }
            } else if ("foreach".equals(tag)) {
                String item = child.getAttribute("item");
                if (item != null && !item.isBlank()) {
                    inScope.add(item);
                }
                String index = child.getAttribute("index");
                if (index != null && !index.isBlank()) {
                    inScope.add(index);
                }
            }
            addScopedNames(child, inScope);
        }
    }

    private static List<Element> collectStatements(Element mapper) {
        List<Element> statements = new ArrayList<>();
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if (STATEMENT_TAGS.contains(element.getTagName())) {
                statements.add(element);
                continue;
            }
            statements.addAll(collectStatements(element));
        }
        return statements;
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

    private static Diagnostic diagnosticForExpression(String xml,
                                                     Path displayFile,
                                                     String uri,
                                                     MapperMethodInfo method,
                                                     String root,
                                                     String rawSnippet,
                                                     int lowerBound) {
        int positionIndex = xml.indexOf(rawSnippet, lowerBound);
        if (positionIndex < 0) {
            positionIndex = xml.indexOf(root, lowerBound);
        }
        if (positionIndex < 0) {
            positionIndex = lowerBound;
        }
        Position pos = toPosition(xml, positionIndex);
        return new Diagnostic(displayFile, uri, Range.at(pos.getLine(), pos.getCharacter()), Severity.ERROR,
                RuleIds.UNDEFINED_PARAMETER,
                "Undefined parameter '" + root + "' in mapper method '" + method.getName() + "'.");
    }

    private static int findStartOfElement(String xml, Element element, int lowerBound) {
        String tag = element.getTagName();
        String id = element.getAttribute("id");
        String search = "<" + tag;
        if (id == null || id.isBlank()) {
            int index = xml.indexOf(search, lowerBound);
            return index >= 0 ? index : lowerBound;
        }
        String idFragment = "id=\"" + id + "\"";
        int index = xml.indexOf(search, lowerBound);
        while (index >= 0) {
            int close = xml.indexOf('>', index);
            if (close >= 0) {
                String snippet = xml.substring(index, close + 1);
                if (snippet.contains(idFragment) || snippet.contains("id='" + id + "'")) {
                    return index;
                }
            }
            index = xml.indexOf(search, index + 1);
        }
        return lowerBound;
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
