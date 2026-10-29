package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperMethodInfo;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    // #{user.name} のように、引数の名前のすぐ後ろにプロパティを 1 つ書いた形(その先は問わない)。
    private static final Pattern NESTED_PROPERTY =
            Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)(?:[.\\[].*)?");

    private final JavaMapperIndex index;
    private final JavaTypeIndex typeIndex;

    public MapperParameterValidator(JavaMapperIndex index) {
        this(index, JavaTypeIndex.empty());
    }

    public MapperParameterValidator(JavaMapperIndex index, JavaTypeIndex typeIndex) {
        this.index = index;
        this.typeIndex = typeIndex == null ? JavaTypeIndex.empty() : typeIndex;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        if (index == null || index.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String xml = XmlSources.readString(actualFile);
            Document document = XmlSources.parse(actualFile);
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
                Set<String> allowedNames;
                // @Param のない Bean 1 つが引数なら、#{...} の名前はその Bean のプロパティを指す。
                String beanType = null;
                if (method.isSingleUnannotatedParameter()) {
                    allowedNames = namesForSingleUnannotatedParameter(method);
                    if (allowedNames == null) {
                        beanType = typeIndex.resolveJavaTypeName(method.getParameterTypes().get(0));
                        allowedNames = beanPropertyNames(beanType);
                    }
                } else {
                    allowedNames = method.availableNames();
                }
                if (allowedNames == null) {
                    continue;
                }
                Set<String> scopedNames = new HashSet<>();
                addScopedNames(statement, scopedNames);
                Set<String> statementScope = new LinkedHashSet<>(allowedNames);
                statementScope.addAll(scopedNames);
                Context context = new Context(method, beanType, xml, displayFile, uri, diagnostics);
                validateIfAndWhenTests(statement, statementScope, context);
                validateTemplateReferences(statement, statementScope, scopedNames, context);
                validateForeachCollections(statement, statementScope, context);
                validateForeachItemProperties(statement, context);
            }
            return diagnostics;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 1 つの statement を検証するあいだ共通の情報。 */
    private record Context(MapperMethodInfo method, String beanType, String xml, Path displayFile, String uri,
                           List<Diagnostic> diagnostics) {
    }

    /** Bean の読めるプロパティと、MyBatis が付ける組み込みの名前。判断できなければ null。 */
    private Set<String> beanPropertyNames(String qualifiedName) {
        Set<String> properties = typeIndex.findReadableProperties(qualifiedName);
        if (properties == null) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>(properties);
        names.add("_parameter");
        names.add("_databaseId");
        return names;
    }

    private void validateTemplateReferences(Element statement,
                                            Set<String> allowedNames,
                                            Set<String> scopedNames,
                                            Context context) {
        String xml = context.xml();
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
                context.diagnostics().add(diagnosticForExpression(context, root, raw, searchOffset));
            }
            if (context.beanType() == null) {
                validateNestedProperty(expression, scopedNames, raw, searchOffset, context);
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
     * そのプロパティを、単純型なら任意の名前で値そのものを指す。その場合は null を返し、
     * 呼び出し側で Bean のプロパティを確かめる(確かめられなければ検証しない)。
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

    /**
     * {@code #{user.userName}} の {@code userName} が、引数 {@code user} の型のプロパティにあるか。
     * {@code <bind>} や {@code <foreach>} の変数と同じ名前なら、引数ではないので見ない。
     */
    private void validateNestedProperty(String expression, Set<String> scopedNames, String raw, int searchOffset,
                                        Context context) {
        Matcher nested = NESTED_PROPERTY.matcher(expression.trim());
        if (!nested.matches() || scopedNames.contains(nested.group(1))) {
            return;
        }
        String parameter = nested.group(1);
        String property = nested.group(2);
        String parameterType = context.method().namedParameterTypes().get(parameter);
        String qualifiedName = typeIndex.resolveJavaTypeName(parameterType);
        Set<String> properties = typeIndex.findReadableProperties(qualifiedName);
        if (properties == null || properties.contains(property)) {
            return;
        }
        String xml = context.xml();
        int snippetStart = xml.indexOf(raw, searchOffset);
        int propertyStart = snippetStart < 0 ? -1 : xml.indexOf(parameter + "." + property, snippetStart);
        Range range = propertyStart < 0
                ? XmlPositions.range(xml, searchOffset, searchOffset)
                : XmlPositions.range(xml, propertyStart + parameter.length() + 1,
                        propertyStart + parameter.length() + 1 + property.length());
        context.diagnostics().add(new Diagnostic(context.displayFile(), context.uri(), range, Severity.ERROR,
                RuleIds.UNKNOWN_PARAMETER_PROPERTY,
                "Unknown property '" + property + "' of parameter '" + parameter + "' (" + qualifiedName
                        + ") in mapper method '" + context.method().getName() + "'."));
    }

    /**
     * {@code <foreach collection="users" item="u">} の中の {@code #{u.userName}} の
     * {@code userName} が、コレクションの要素の型のプロパティにあるか。
     *
     * <p>要素の型は、collection に引数の名前を書いたときの {@code List<UserDomain>} や
     * {@code UserDomain[]} から決める。{@code @Param} のない引数 1 つなら
     * {@code list} / {@code collection} / {@code array} でも決める。プロパティの判断は
     * {@code #{user.userName}} と同じで、子クラスがある型などは検証しない。</p>
     */
    private void validateForeachItemProperties(Element statement, Context context) {
        String xml = context.xml();
        int cursor = findStartOfElement(xml, statement, 0);
        List<Element> elements = new ArrayList<>();
        collectDescendants(statement, elements);
        for (Element foreach : elements) {
            if (!"foreach".equals(foreach.getTagName())) {
                continue;
            }
            int foreachStart = xml.indexOf("<foreach", cursor);
            if (foreachStart >= 0) {
                cursor = foreachStart + 1;
            }
            String item = foreach.getAttribute("item").trim();
            String elementType = foreachElementType(foreach.getAttribute("collection").trim(), context.method());
            if (item.isEmpty() || elementType == null) {
                continue;
            }
            String qualifiedName = typeIndex.resolveJavaTypeName(elementType);
            Set<String> properties = typeIndex.findReadableProperties(qualifiedName);
            if (properties == null) {
                continue;
            }
            Matcher matcher = TEMPLATE_PATTERN.matcher(textUsingItem(foreach, item));
            int searchFrom = Math.max(foreachStart, 0);
            while (matcher.find()) {
                String expression = matcher.group(1) == null
                        ? matcher.group(2)
                        : propertyPartOfParameterExpression(matcher.group(1));
                Matcher nested = expression == null ? null : NESTED_PROPERTY.matcher(expression.trim());
                if (nested == null || !nested.matches() || !nested.group(1).equals(item)) {
                    continue;
                }
                String property = nested.group(2);
                int found = findIdentifier(xml, item + "." + property, searchFrom, xml.length());
                if (found >= 0) {
                    searchFrom = found + 1;
                }
                if (properties.contains(property)) {
                    continue;
                }
                Range range = found < 0
                        ? XmlPositions.range(xml, Math.max(foreachStart, 0), Math.max(foreachStart, 0))
                        : XmlPositions.range(xml, found + item.length() + 1, found + item.length() + 1 + property.length());
                context.diagnostics().add(new Diagnostic(context.displayFile(), context.uri(), range, Severity.ERROR,
                        RuleIds.UNKNOWN_PARAMETER_PROPERTY,
                        "Unknown property '" + property + "' of foreach item '" + item + "' (" + qualifiedName
                                + ") in mapper method '" + context.method().getName() + "'."));
            }
        }
    }

    /** collection に書いた引数のコレクション/配列の要素の型。決められなければ null。 */
    private static String foreachElementType(String collection, MapperMethodInfo method) {
        if (!collection.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return null;
        }
        String parameterType;
        if (method.isSingleUnannotatedParameter()) {
            boolean builtInName = Set.of("list", "collection", "array").contains(collection);
            if (!builtInName && !collection.equals(method.getParameterNames().get(0))) {
                return null;
            }
            parameterType = method.getParameterTypes().get(0).trim();
            if (method.isSingleParameterVarArgs()) {
                return parameterType;
            }
        } else {
            parameterType = method.namedParameterTypes().get(collection);
            if (parameterType == null) {
                return null;
            }
            parameterType = parameterType.trim();
        }
        if (parameterType.endsWith("[]")) {
            String element = parameterType.substring(0, parameterType.length() - 2).trim();
            return element.contains("[") ? null : element;
        }
        int open = parameterType.indexOf('<');
        if (open < 0 || !parameterType.endsWith(">")) {
            return null;
        }
        String container = parameterType.substring(0, open).trim();
        String containerSimple = container.substring(container.lastIndexOf('.') + 1);
        String element = parameterType.substring(open + 1, parameterType.length() - 1).trim();
        // Map は値の型が要素になり、ワイルドカードや入れ子の型引数は要素の型を決めきれない。
        if (!COLLECTION_TYPES.contains(containerSimple) || element.contains("<") || element.contains(",")
                || element.contains("?")) {
            return null;
        }
        return element;
    }

    /**
     * foreach の中で item を参照している SQL。同じ名前の item を持つ内側の foreach や、
     * 同じ名前の bind の中は、別の変数なので含めない。
     */
    private static String textUsingItem(Element foreach, String item) {
        StringBuilder text = new StringBuilder();
        NodeList children = foreach.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                boolean redefines = ("foreach".equals(element.getTagName()) && item.equals(element.getAttribute("item")))
                        || ("bind".equals(element.getTagName()) && item.equals(element.getAttribute("name")));
                if (redefines) {
                    // bind 以降の兄弟でも item は別の値を指すので、ここで打ち切る。
                    if ("bind".equals(element.getTagName())) {
                        break;
                    }
                    continue;
                }
                text.append(textUsingItem(element, item));
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        return text.toString();
    }

    private static void validateIfAndWhenTests(Element statement,
                                               Set<String> allowedNames,
                                               Context context) {
        String xml = context.xml();
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
                context.diagnostics().add(diagnosticForExpression(context, root, expression, lowerBound));
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
                                                    Context context) {
        String xml = context.xml();
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
                context.diagnostics().add(diagnosticForExpression(context, root, expression, elementStart));
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

    private static Diagnostic diagnosticForExpression(Context context,
                                                     String root,
                                                     String rawSnippet,
                                                     int lowerBound) {
        String xml = context.xml();
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
        if (context.beanType() != null) {
            return new Diagnostic(context.displayFile(), context.uri(), range, Severity.ERROR,
                    RuleIds.UNKNOWN_PARAMETER_PROPERTY,
                    "Unknown property '" + root + "' of parameter type '" + context.beanType()
                            + "' in mapper method '" + context.method().getName() + "'.");
        }
        return new Diagnostic(context.displayFile(), context.uri(), range, Severity.ERROR,
                RuleIds.UNDEFINED_PARAMETER,
                "Undefined parameter '" + root + "' in mapper method '" + context.method().getName() + "'.");
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
