package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperMethodInfo;
import com.mybatislint.analyzer.model.Diagnostic;
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
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "Collection", "List", "ArrayList", "LinkedList", "Set", "HashSet", "LinkedHashSet", "TreeSet");

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
                Set<String> allowedNames = method.isSingleUnannotatedParameter()
                        ? namesForSingleUnannotatedParameter(method)
                        : method.availableNames();
                if (allowedNames == null) {
                    continue;
                }
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
            String expression = matcher.group(1) == null
                    ? matcher.group(2)
                    : propertyPartOfParameterExpression(matcher.group(1));
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

    /**
     * {@code #{item.id,jdbcType=NUMERIC}} や旧形式の {@code #{id:NUMERIC}} から、
     * パラメータを指す先頭部分だけを取り出す。{@code jdbcType} や {@code NUMERIC} は
     * マッピングの指定であって変数参照ではない。{@code #{(expr)}} 形式は中身を
     * 正しく解釈できないので null を返して検証しない。
     */
    private static String propertyPartOfParameterExpression(String expression) {
        String trimmed = expression.trim();
        if (trimmed.startsWith("(")) {
            return null;
        }
        int end = trimmed.length();
        int comma = trimmed.indexOf(',');
        if (comma >= 0) {
            end = comma;
        }
        int colon = trimmed.indexOf(':');
        if (colon >= 0 && colon < end) {
            end = colon;
        }
        return trimmed.substring(0, end);
    }

    /**
     * 引数が 1 つで {@code @Param} がないときの扱いを決める。MyBatis はこの場合、
     * 引数オブジェクトそのものをパラメータにするので、Bean なら {@code #{name}} は
     * そのプロパティを、単純型なら任意の名前で値そのものを指す。プロパティを
     * 確かめる手段がないため、検証自体をしない(null を返す)。
     * コレクションと配列だけは MyBatis が {@code collection} / {@code list} /
     * {@code array} という名前で包むので、それらを許可して検証を続ける。
     */
    private static Set<String> namesForSingleUnannotatedParameter(MapperMethodInfo method) {
        String type = method.getParameterTypes().get(0).trim();
        String raw = type.contains("<") ? type.substring(0, type.indexOf('<')).trim() : type;
        String simple = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        boolean array = type.endsWith("[]") || method.isSingleParameterVarArgs();
        boolean collection = COLLECTION_TYPES.contains(simple);
        if (!array && !collection) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>(method.availableNames());
        names.add("collection");
        names.add("list");
        names.add("array");
        return names;
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
        // エディタでは未定義の名前そのものに波線を引きたいので、式の中からその名前を探す。
        // 見つからなければ式全体、それも見つからなければ幅 0 の位置にする。
        Range range;
        int snippetStart = xml.indexOf(rawSnippet, lowerBound);
        int searchEnd = snippetStart >= 0 ? snippetStart + rawSnippet.length() : xml.length();
        int rootIndex = findIdentifier(xml, root, snippetStart >= 0 ? snippetStart : lowerBound, searchEnd);
        if (rootIndex >= 0) {
            range = XmlPositions.range(xml, rootIndex, rootIndex + root.length());
        } else if (snippetStart >= 0) {
            range = XmlPositions.range(xml, snippetStart, searchEnd);
        } else {
            range = XmlPositions.range(xml, lowerBound, lowerBound);
        }
        return new Diagnostic(displayFile, uri, range, Severity.ERROR,
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

    /** {@code [from, to)} の範囲で、前後が識別子の文字でない {@code name} を探す。 */
    private static int findIdentifier(String xml, String name, int from, int to) {
        int index = xml.indexOf(name, from);
        while (index >= 0 && index + name.length() <= to) {
            int before = index - 1;
            int after = index + name.length();
            boolean startsWord = before < 0
                    || (!Character.isJavaIdentifierPart(xml.charAt(before)) && xml.charAt(before) != '.');
            boolean endsWord = after >= xml.length() || !Character.isJavaIdentifierPart(xml.charAt(after));
            if (startsWord && endsWord) {
                return index;
            }
            index = xml.indexOf(name, index + 1);
        }
        return -1;
    }
}
