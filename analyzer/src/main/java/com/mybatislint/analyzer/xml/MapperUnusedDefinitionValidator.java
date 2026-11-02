package com.mybatislint.analyzer.xml;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.DeclaredMethod;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaSources;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * どこからも使われていない定義を見つける。消し忘れや、名前を変えたときの直し忘れに気づくため。
 * どれも実行時のエラーにはならないので warning にする。
 *
 * <h2>{@code mybatis-lint/unused-sql-fragment}</h2>
 * <p>どの {@code <include refid>} からも参照されていない {@code <sql>}。</p>
 *
 * <h2>{@code mybatis-lint/unused-result-map}</h2>
 * <p>statement の {@code resultMap}、association / collection / case の {@code resultMap}、
 * {@code <resultMap extends>}、Java の {@code @ResultMap} のどれからも参照されていない {@code <resultMap>}。</p>
 *
 * <h2>{@code mybatis-lint/statement-without-method}</h2>
 * <p>namespace のインターフェースに、同じ名前のメソッドがない statement。</p>
 *
 * <h2>判断できない場合は何もしない</h2>
 * <ul>
 *   <li>読めない mapper XML がある(どこを参照しているかわからない)と、sql と resultMap は見ない</li>
 *   <li>{@code refid} に {@code ${...}} がある、{@code resultMap} に {@code ${...}} がある、
 *       {@code @ResultMap} の値が文字列リテラルでない場合は、その種類の定義を見ない</li>
 *   <li>statement は、namespace と同じ FQCN のインターフェースがワークスペースにあり、
 *       ほかのインターフェースを extends していない場合だけ見る。namespace だけの mapper は
 *       {@code sqlSession.selectList("namespace.id")} のように文字列で呼ばれる</li>
 *   <li>Java のどこかに {@code "namespace.id"} や {@code "id"} という文字列リテラルがある statement は、
 *       文字列で呼ばれているかもしれないので見ない</li>
 * </ul>
 */
public final class MapperUnusedDefinitionValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private final List<Path> mapperFiles;
    private final JavaMapperIndex javaIndex;
    private final JavaSources javaSources;

    public MapperUnusedDefinitionValidator(List<Path> mapperFiles, JavaMapperIndex javaIndex, JavaSources javaSources) {
        this.mapperFiles = mapperFiles;
        this.javaIndex = javaIndex == null ? JavaMapperIndex.empty() : javaIndex;
        this.javaSources = javaSources == null ? JavaSources.empty() : javaSources;
    }

    /** 定義の場所。 */
    private record Definition(Path file, String xml, String tag, String rawId) {
    }

    /** @param displayPath ファイルの表示用パス(ワークスペースからの相対パス)を求める関数 */
    public List<Diagnostic> validate(Function<Path, Path> displayPath) {
        Map<String, Definition> sqlFragments = new HashMap<>();
        Map<String, Definition> resultMaps = new HashMap<>();
        Set<String> usedSql = new HashSet<>();
        Set<String> usedResultMaps = new HashSet<>();
        // ${tenant}.columns のように namespace だけが動的な参照の id。どの namespace の同じ id も使われうる。
        Set<String> usedSqlIds = new HashSet<>();
        Set<String> usedResultMapIds = new HashSet<>();
        // 参照先を決められないものがあれば、その種類の定義は「使われていない」と言い切れない。
        boolean sqlKnown = true;
        boolean resultMapsKnown = collectJavaResultMaps(usedResultMaps);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> javaStrings = javaStringLiterals();
        Set<String> brokenNamespaces = new HashSet<>();

        for (Path file : mapperFiles) {
            String xml;
            Element mapper;
            try {
                xml = XmlSources.readString(file);
                mapper = XmlSources.parse(file).getDocumentElement();
            } catch (Exception e) {
                // 編集中などで壊れた mapper XML の参照は読めない。壊れたファイルが別の namespace の
                // 定義を完全修飾で参照することはまれなので、その namespace の定義だけを見ない。
                MapperStatementIndex.BrokenMapper broken = MapperStatementIndex.inspectBrokenFile(file);
                if (broken == MapperStatementIndex.BrokenMapper.UNKNOWN) {
                    sqlKnown = false;
                    resultMapsKnown = false;
                } else if (broken != MapperStatementIndex.BrokenMapper.NOT_A_MAPPER) {
                    brokenNamespaces.add(broken.namespace());
                }
                continue;
            }
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                continue;
            }
            String namespace = mapper.getAttribute("namespace").trim();
            if (namespace.isEmpty()) {
                // namespace のない mapper XML は MyBatis が読み込めない(missing-namespace で報告する)。
                // その中の参照は使われないので、飛ばすだけでよい。
                continue;
            }
            if (namespace.contains("${")) {
                // 参照の短い id がどの namespace を指すかわからない。
                sqlKnown = false;
                resultMapsKnown = false;
                continue;
            }
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element child)) {
                    continue;
                }
                String id = child.getAttribute("id").trim();
                if (id.isEmpty() || id.contains("${")) {
                    continue;
                }
                if ("sql".equals(child.getTagName())) {
                    put(sqlFragments, qualify(id, namespace), new Definition(file, xml, "sql", id));
                } else if ("resultMap".equals(child.getTagName())) {
                    put(resultMaps, qualify(id, namespace), new Definition(file, xml, "resultMap", id));
                } else if (STATEMENT_TAGS.contains(child.getTagName())) {
                    checkStatement(namespace, child.getTagName(), id, file, xml, javaStrings, displayPath, diagnostics);
                }
            }
            for (Element element : descendants(mapper)) {
                if ("include".equals(element.getTagName())) {
                    String refid = element.getAttribute("refid").trim();
                    if (refid.contains("${")) {
                        sqlKnown &= addDynamicReference(refid, usedSqlIds);
                    } else if (!refid.isEmpty()) {
                        usedSql.add(qualify(refid, namespace));
                    }
                }
                List<String> references = new ArrayList<>(List.of(element.getAttribute("resultMap").split(",")));
                if ("resultMap".equals(element.getTagName())) {
                    references.add(element.getAttribute("extends"));
                }
                for (String reference : references) {
                    String trimmed = reference.trim();
                    if (trimmed.contains("${")) {
                        resultMapsKnown &= addDynamicReference(trimmed, usedResultMapIds);
                    } else if (!trimmed.isEmpty()) {
                        usedResultMaps.add(qualify(trimmed, namespace));
                    }
                }
            }
        }

        if (sqlKnown) {
            reportUnused(sqlFragments, usedSql, usedSqlIds, brokenNamespaces, RuleIds.UNUSED_SQL_FRAGMENT,
                    "<sql id=\"%s\"> is not used by any <include refid>.", displayPath, diagnostics);
        }
        if (resultMapsKnown) {
            reportUnused(resultMaps, usedResultMaps, usedResultMapIds, brokenNamespaces, RuleIds.UNUSED_RESULT_MAP,
                    "<resultMap id=\"%s\"> is not used by any statement, nested mapping, extends or @ResultMap.",
                    displayPath, diagnostics);
        }
        // 同じ実行で結果が変わらないよう、ファイルと位置の順に並べる。
        diagnostics.sort((a, b) -> {
            int byFile = a.getFile().toString().compareTo(b.getFile().toString());
            return byFile != 0 ? byFile : Integer.compare(a.getRange().getStart().getLine(), b.getRange().getStart().getLine());
        });
        return diagnostics;
    }

    private void checkStatement(String namespace, String tag, String id, Path file, String xml, Set<String> javaStrings,
                                Function<Path, Path> displayPath, List<Diagnostic> diagnostics) {
        MapperInterfaceInfo mapperInterface = javaIndex.interfaces().stream()
                .filter(candidate -> candidate.getQualifiedName().equals(namespace))
                .findFirst().orElse(null);
        if (mapperInterface == null || mapperInterface.isDuplicated() || mapperInterface.extendsOtherInterfaces()) {
            return;
        }
        // MyBatis は namespace 付きの id も受け付ける。
        String name = id.startsWith(namespace + ".") ? id.substring(namespace.length() + 1) : id;
        boolean declared = mapperInterface.getDeclaredMethods().stream().map(DeclaredMethod::name).anyMatch(name::equals);
        if (declared || javaStrings.contains(namespace + "." + name) || javaStrings.contains(name)) {
            return;
        }
        diagnostics.add(new Diagnostic(displayPath.apply(file), file.toUri().toString(), idRange(xml, tag, id),
                Severity.WARNING, RuleIds.STATEMENT_WITHOUT_METHOD,
                "Statement '" + id + "' has no method of that name in " + mapperInterface.getQualifiedName()
                        + ", so it cannot be called through the mapper interface."));
    }

    private static void reportUnused(Map<String, Definition> definitions, Set<String> used, Set<String> usedIds,
                                     Set<String> brokenNamespaces, String ruleId, String format,
                                     Function<Path, Path> displayPath, List<Diagnostic> diagnostics) {
        definitions.forEach((qualified, definition) -> {
            // 同じ id が複数あるもの(別ルールで報告する)は、どれが使われているか決めない。
            if (definition == null || used.contains(qualified) || usedIds.contains(definition.rawId())
                    || brokenNamespaces.contains(qualified.substring(0, qualified.lastIndexOf('.')))) {
                return;
            }
            diagnostics.add(new Diagnostic(displayPath.apply(definition.file()), definition.file().toUri().toString(),
                    idRange(definition.xml(), definition.tag(), definition.rawId()), Severity.WARNING, ruleId,
                    String.format(format, definition.rawId())));
        });
    }

    /**
     * {@code ${...}} を含む参照。{@code ${tenant}.columns} のように id の部分が決まっていれば、その id を
     * 覚えて true を返す。id の部分が動的なら、どれを参照しているかわからないので false。
     */
    private static boolean addDynamicReference(String reference, Set<String> usedIds) {
        String id = reference.substring(reference.lastIndexOf('.') + 1);
        if (id.isEmpty() || id.contains("${") || id.contains("}")) {
            return false;
        }
        usedIds.add(id);
        return true;
    }

    private static void put(Map<String, Definition> definitions, String key, Definition definition) {
        definitions.put(key, definitions.containsKey(key) ? null : definition);
    }

    /** 短い id はその mapper の namespace のもの。{@code .} を含む id は完全修飾として扱う(MyBatis と同じ)。 */
    private static String qualify(String id, String namespace) {
        return id.contains(".") ? id : namespace + "." + id;
    }

    /**
     * Java の {@code @ResultMap("id")} が参照する resultMap を集める。値が文字列リテラルでないもの
     * (定数など)があれば、どれを参照しているかわからないので false。
     */
    private boolean collectJavaResultMaps(Set<String> used) {
        for (JavaSources.ParsedFile parsed : javaSources.files()) {
            for (AnnotationExpr annotation : parsed.unit().findAll(AnnotationExpr.class)) {
                if (!"ResultMap".equals(annotation.getName().getIdentifier())) {
                    continue;
                }
                Expression value = annotation instanceof SingleMemberAnnotationExpr single ? single.getMemberValue()
                        : annotation instanceof NormalAnnotationExpr normal ? normal.getPairs().stream()
                        .filter(pair -> "value".equals(pair.getNameAsString())).map(pair -> pair.getValue())
                        .findFirst().orElse(null)
                        : null;
                List<Expression> values = value instanceof ArrayInitializerExpr array ? array.getValues()
                        : value == null ? List.of() : List.of(value);
                String namespace = annotation.findAncestor(ClassOrInterfaceDeclaration.class)
                        .flatMap(type -> type.getFullyQualifiedName()).orElse(null);
                for (Expression expression : values) {
                    if (!(expression instanceof StringLiteralExpr literal) || namespace == null) {
                        return false;
                    }
                    used.add(qualify(literal.getValue().trim(), namespace));
                }
            }
        }
        return true;
    }

    /** Java のソースにある文字列リテラル。statement を文字列の id で呼んでいるかを見るため。 */
    private Set<String> javaStringLiterals() {
        Set<String> strings = new HashSet<>();
        for (JavaSources.ParsedFile parsed : javaSources.files()) {
            parsed.unit().findAll(StringLiteralExpr.class).forEach(literal -> strings.add(literal.getValue()));
        }
        return strings;
    }

    private static List<Element> descendants(Element root) {
        List<Element> elements = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            elements.add((Element) all.item(i));
        }
        return elements;
    }

    /** {@code <tag ... id="id">} の id の値の範囲。見つからなければ文書の先頭。 */
    private static Range idRange(String xml, String tag, String id) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tag) + "(?=[\\s>/])").matcher(xml);
        while (matcher.find()) {
            int valueStart = XmlPositions.findAttributeValueStart(xml, matcher.start(), "id", id);
            if (valueStart >= 0) {
                return XmlPositions.range(xml, valueStart, valueStart + id.length());
            }
        }
        return Range.at(1, 1);
    }
}
