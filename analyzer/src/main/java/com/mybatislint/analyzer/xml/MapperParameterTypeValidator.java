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
 * Mapper XML の statement 上の {@code parameterType="..."} と、対応する
 * Java Mapper メソッドの実引数型を突き合わせる検証器。
 *
 * <h2>{@code mybatis-lint/unresolved-type-alias} との責務分担</h2>
 *
 * <p>この検証器は {@link MapperReferenceValidator#validateTypeAliases} の
 * {@code unresolved-type-alias} ルールと役割が近く見えるが、見ている情報も
 * 判定できることも異なるため、役割を明確に分けている。</p>
 *
 * <ul>
 *   <li><b>{@code unresolved-type-alias}(既存)</b>:
 *       parameterType/resultType/{@code <resultMap type>} の「文字列」だけを見て、
 *       ワークスペース内に同名 simple name のクラスがあるかどうかで
 *       「怪しい alias 候補」を判定する。Java Mapper メソッドの引数とは
 *       まったく突き合わせない。resultType にも type にも適用される、
 *       より汎用的な heuristic。</li>
 *   <li><b>{@code parameter-type-mismatch}(この検証器)</b>:
 *       XML の parameterType「そのもの」の解決可否ではなく、
 *       「単一引数の Java Mapper メソッドが一意に解決できたとき、その
 *       実引数型と parameterType が食い違っていないか」を見る。
 *       resultType や {@code <resultMap type>} は対象外(Java 側に対応する
 *       「戻り値の型」という明確な 1 個の比較対象が無く、誤検知のリスクが
 *       高くなるため、まずは parameterType だけに絞っている)。</li>
 * </ul>
 *
 * <p>つまり、typeAlias ルールは「文字列としてこの alias は解決できそうか」、
 * こちらのルールは「Java 側で実際に読み取れた型と矛盾していないか」を見ている。
 * 両方とも warning に留めているのは、どちらも静的解析だけでは 100% 断定できない
 * ケースが残るためである(詳細は各セクションのコメントを参照)。</p>
 */
public final class MapperParameterTypeValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    // MyBatis 組み込み typeAlias のうち、「Java の具体的な型」と自然に対応付け
    // できるものだけを、比較カテゴリごとに整理したもの。
    //
    // 設計判断: primitive/boxed/String/Date/BigDecimal/BigInteger は
    // MyBatis の TypeAliasRegistry が登録するクラスが 1 つに定まる、閉じた
    // 小さな集合なので、ここに載っていない Java 型が来たら「明らかな不一致」
    // として warning を出してよいと判断した(exact match 方式)。
    //
    // 一方 map/list/set/collection 系は、ユーザーが独自の実装クラス
    // (例: 自作の `UserList implements List<User>`)を使うことも珍しくなく、
    // simple name の末尾が "Map"/"List"/"Set" などになっていない限り
    // 静的には判別できない。誤検知の方が実害が大きいため、この系統は
    // 「一致は積極的に認める」が「不一致は primitive/String など明らかに
    // 無関係な型のときだけ warning にし、それ以外はフェイルオープンする」
    // という非対称なルールにしている(下記 collectionLikeMatch 参照)。
    private static final Map<String, Set<String>> EXACT_ALIAS_JAVA_TYPES = Map.ofEntries(
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

    // "object" alias は Object.class に登録されており、実質どんな型でも
    // 受け入れられるため、比較の意味がない(フェイルオープン=常に一致扱い)。
    private static final String OBJECT_ALIAS = "object";

    // Map 系 / Collection 系 / その他コレクション由来 alias。
    // これらは exact match ではなく collectionLikeMatch() 側の非対称ルールで判定する。
    private static final Set<String> MAP_LIKE_ALIASES =
            Set.of("map", "hashmap", "linkedhashmap", "treemap");
    private static final Set<String> LIST_LIKE_ALIASES = Set.of("list", "arraylist");
    private static final Set<String> SET_LIKE_ALIASES =
            Set.of("set", "hashset", "linkedhashset", "treeset");
    private static final Set<String> COLLECTION_ALIAS = Set.of("collection");
    private static final Set<String> OTHER_COLLECTION_LIKE_ALIASES =
            Set.of("iterator", "iterable", "cursor", "resultset");

    private final JavaMapperIndex index;

    public MapperParameterTypeValidator(JavaMapperIndex index) {
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
                // namespace に対応する Java Mapper インターフェースが見つからない場合、
                // このワークスペースには単に Java 側が含まれていないだけかもしれない。
                // 「対応するインターフェースが無い」ことと「不一致がある」ことは
                // まったく別の意味を持つため、ここは常にフェイルオープンする。
                return Collections.emptyList();
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            int searchOffset = 0;
            for (Element statement : collectStatements(mapper)) {
                int elementStart = findStartOfElement(xml, statement, searchOffset);
                searchOffset = elementStart + ("<" + statement.getTagName()).length();

                String methodId = statement.getAttribute("id");
                if (methodId == null || methodId.isBlank()) {
                    continue;
                }
                // resolveMethod は同名メソッドが複数(オーバーロード)ある場合や
                // 見つからない場合に null を返す。曖昧な状態から「引数はこれだ」と
                // 決め打ちすると誤検知の原因になるため、ここでも素直にスキップする。
                MapperMethodInfo method = mapperInterface.resolveMethod(methodId);
                if (method == null) {
                    continue;
                }
                // スコープは単一引数メソッドだけ。0 個(引数不要)や 2 個以上
                // (通常は @Param 付きの複合パラメータになり、parameterType には
                // Map.class が暗黙的に使われる)は、このルールでは判定が難しいため
                // 対象外にする。
                if (method.getParameterCount() != 1) {
                    continue;
                }
                if (method.isSingleParameterVarArgs()) {
                    continue;
                }

                String parameterType = statement.getAttribute("parameterType");
                if (parameterType == null || parameterType.isBlank()) {
                    continue;
                }
                String trimmedXmlType = parameterType.trim();
                if (trimmedXmlType.contains("${")) {
                    // <include refid> や typeAlias 検証と同じ理由で、実行時の
                    // プロパティ置換により値が変わるため静的には判断できない。
                    continue;
                }

                String javaRawType = method.getParameterTypes().get(0);
                MatchResult result = compare(trimmedXmlType, javaRawType);
                if (result != MatchResult.MISMATCH) {
                    continue;
                }
                diagnostics.add(new Diagnostic(displayFile, uri,
                        rangeForAttributeValue(xml, statement, "parameterType", parameterType, elementStart),
                        Severity.WARNING,
                        RuleIds.PARAMETER_TYPE_MISMATCH,
                        "Statement parameterType '" + parameterType + "' does not match the single argument type '"
                                + javaRawType + "' of mapper method '" + method.getName() + "'."));
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方自体は MapperXmlAnalyzer の SAX パースが既に診断済みのはず。
            // ここで二重に報告すると原因が読みにくくなるので、フェイルオープンする。
            return Collections.emptyList();
        }
    }

    private enum MatchResult {
        MATCH,
        MISMATCH,
        // 「一致とも不一致とも確信を持てない」ケース。呼び出し側では
        // MISMATCH 以外はすべて「報告しない」という扱いにしている。
        UNKNOWN
    }

    /**
     * XML 側の parameterType 文字列と、Java 側の宣言型文字列(ソースの見た目)を
     * 比較する。判定方針は要件どおり 3 段階:
     *
     * <ol>
     *   <li>XML が完全修飾クラス名(ドットを含む) → 末尾の simple name を
     *       Java 型の simple name と比較する。</li>
     *   <li>XML が MyBatis 組み込み alias → primitive/boxed/String/Map/List系との
     *       対応表で比較する。</li>
     *   <li>それ以外(simple name 風) → Java 型の simple name と直接比較する。
     *       ただし小文字始まりは個別登録 alias の可能性を否定できないため
     *       {@code unresolved-type-alias} と同じ理由でフェイルオープンする。</li>
     * </ol>
     */
    private static MatchResult compare(String xmlValue, String javaRawType) {
        JavaTypeShape javaType = JavaTypeShape.parse(javaRawType);
        if (javaType == null) {
            // ジェネリクスの型変数(T, E など)や配列型など、simple name だけでは
            // 安全に断定できない Java 型は比較そのものを諦める。
            return MatchResult.UNKNOWN;
        }

        if (xmlValue.contains(".")) {
            String xmlSimpleName = xmlValue.substring(xmlValue.lastIndexOf('.') + 1);
            return xmlSimpleName.equals(javaType.simpleName) ? MatchResult.MATCH : MatchResult.MISMATCH;
        }

        String aliasLower = xmlValue.toLowerCase(Locale.ROOT);
        if (OBJECT_ALIAS.equals(aliasLower)) {
            // Object は何でも受け入れられるため、比較する意味がない。
            return MatchResult.MATCH;
        }
        Set<String> exactCandidates = EXACT_ALIAS_JAVA_TYPES.get(aliasLower);
        if (exactCandidates != null) {
            return exactCandidates.contains(javaType.simpleName) ? MatchResult.MATCH : MatchResult.MISMATCH;
        }
        if (isCollectionLikeAlias(aliasLower)) {
            return collectionLikeMatch(javaType);
        }

        // ここまで来た値は、typeAlias 一覧に無い「simple name 風」の文字列。
        if (javaType.simpleName.equals(xmlValue)) {
            return MatchResult.MATCH;
        }
        if (!Character.isUpperCase(xmlValue.charAt(0))) {
            // unresolved-type-alias と同じ判断: 小文字始まりは
            // mybatis-config.xml 側で個別登録された alias の可能性を
            // 否定できないため、断定を避けてフェイルオープンする。
            return MatchResult.UNKNOWN;
        }
        return MatchResult.MISMATCH;
    }

    private static boolean isCollectionLikeAlias(String aliasLower) {
        return MAP_LIKE_ALIASES.contains(aliasLower) || LIST_LIKE_ALIASES.contains(aliasLower)
                || SET_LIKE_ALIASES.contains(aliasLower) || COLLECTION_ALIAS.contains(aliasLower)
                || OTHER_COLLECTION_LIKE_ALIASES.contains(aliasLower);
    }

    /**
     * map/list/set/collection 系 alias 専用の、非対称な判定。
     *
     * <p>「一致」は simple name の末尾や既知の JDK コレクション名から
     * 積極的に認める一方、「不一致」は Java 側が primitive/boxed/String
     * のような、コレクションではあり得ないことが確実な型のときだけ報告する。
     * それ以外(ワークスペース固有の DTO クラス名など)は、ユーザー定義の
     * コレクション実装かもしれないため断定を避けてフェイルオープンする。</p>
     */
    private static MatchResult collectionLikeMatch(JavaTypeShape javaType) {
        String simpleName = javaType.simpleName;
        if (simpleName.endsWith("Map") || simpleName.endsWith("List") || simpleName.endsWith("Set")
                || simpleName.endsWith("Collection") || "Iterator".equals(simpleName)
                || "Iterable".equals(simpleName) || "Cursor".equals(simpleName)
                || "ResultSet".equals(simpleName)) {
            return MatchResult.MATCH;
        }
        if (isDefinitelyNotCollectionLike(simpleName)) {
            return MatchResult.MISMATCH;
        }
        return MatchResult.UNKNOWN;
    }

    private static boolean isDefinitelyNotCollectionLike(String javaSimpleName) {
        if ("String".equals(javaSimpleName) || "Date".equals(javaSimpleName)
                || "BigDecimal".equals(javaSimpleName) || "BigInteger".equals(javaSimpleName)) {
            return true;
        }
        for (Set<String> candidates : EXACT_ALIAS_JAVA_TYPES.values()) {
            if (candidates.contains(javaSimpleName)) {
                return true;
            }
        }
        return false;
    }

    /** Java 側の宣言型文字列を、比較に使える形へ正規化した結果。 */
    private static final class JavaTypeShape {
        private final String simpleName;

        private JavaTypeShape(String simpleName) {
            this.simpleName = simpleName;
        }

        /**
         * ソースコード上の型表記(例: {@code "java.util.List<User>"}, {@code "int"},
         * {@code "T"})から、比較に使える simple name を取り出す。
         * 安全に判断できないケースは {@code null} を返し、呼び出し側で
         * フェイルオープンさせる。
         */
        static JavaTypeShape parse(String rawType) {
            if (rawType == null || rawType.isBlank()) {
                return null;
            }
            String type = rawType.trim();
            if (type.endsWith("[]")) {
                // 配列型(byte[] など)は今回のスコープ外。組み込み alias の
                // byte[]/date[] のような配列 alias とも突き合わせが必要になり
                // 複雑になるため、まずは単純なケースに絞るという要件どおり
                // 対象外にする。
                return null;
            }
            int genericStart = type.indexOf('<');
            String outer = genericStart >= 0 ? type.substring(0, genericStart) : type;
            outer = outer.trim();
            if (outer.isEmpty() || outer.contains("?")) {
                return null;
            }
            String simple = outer.contains(".") ? outer.substring(outer.lastIndexOf('.') + 1) : outer;
            if (looksLikeGenericTypeVariable(simple)) {
                // "T", "E", "K", "V" のような 1~2 文字の大文字だけの識別子は、
                // 実クラスではなくジェネリクスの型変数である可能性が高い。
                // JavaParser はシンボル解決をしないため、ここでは名前の見た目だけで
                // 判断するしかなく、誤検知を避けるためフェイルオープンする。
                return null;
            }
            return new JavaTypeShape(simple);
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
