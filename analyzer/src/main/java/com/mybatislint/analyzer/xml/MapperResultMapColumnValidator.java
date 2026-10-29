package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * resultMap の {@code <id column>} / {@code <result column>} が、その resultMap を使う
 * {@code <select>} の SELECT 句にない列を見つける({@code mybatis-lint/result-map-column-not-selected})。
 * その列に対応するプロパティは、エラーにならずに null のままになる。
 *
 * <h2>判断できない場合は何もしない</h2>
 *
 * <p>1 つの resultMap を、列の違う複数の select で使い回すのはよくある書き方で、一部の列が
 * SELECT 句にないのは意図したものかもしれない。そのため、ワークスペース全体で
 * <b>ちょうど 1 つの {@code <select resultMap>} からしか使われていない</b> resultMap だけを見る。
 * ほかの resultMap からの参照({@code extends}、association / collection / case の
 * {@code resultMap})がある resultMap も見ない。</p>
 * <ul>
 *   <li>resultMap に {@code <id>} / {@code <result>} 以外の子要素(association、collection、
 *       discriminator、constructor)がある、{@code extends} がある</li>
 *   <li>SELECT 句の列の名前が 1 つでも決まらない({@code *}、別名のない式、FROM より前の
 *       {@code <include>} や {@code <if>} など。{@link MapperSelectAliasValidator} と同じ読み方)</li>
 *   <li>column が空、{@code ${...}} を含む、{@code {a=b}} のような複合の指定</li>
 *   <li>namespace がわからない、読めない mapper XML がある</li>
 * </ul>
 * <p>列名は大文字小文字を区別せずに比べる(Oracle は引用符のない名前を大文字で返し、
 * MyBatis も大文字小文字を区別せずに対応付ける)。</p>
 */
public final class MapperResultMapColumnValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> COLUMN_TAGS = Set.of("id", "result");

    private final List<Path> mapperFiles;

    public MapperResultMapColumnValidator(List<Path> mapperFiles) {
        this.mapperFiles = mapperFiles;
    }

    /** resultMap の宣言と、それを使う select の候補。 */
    private record Declaration(Path file, String xml, Element resultMap) {
    }

    /** resultMap を使っている場所。select の resultMap 属性に 1 つだけ書いたものなら select が入る。 */
    private record Usage(Element select) {
    }

    /** @param displayPath ファイルの表示用パス(ワークスペースからの相対パス)を求める関数 */
    public List<Diagnostic> validate(Function<Path, Path> displayPath) {
        Map<String, Declaration> declarations = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        // 編集中などで壊れた mapper XML の namespace。その resultMap は使われ方がわからない。
        Set<String> brokenNamespaces = new HashSet<>();
        Map<String, List<Usage>> usages = new HashMap<>();
        for (Path file : mapperFiles) {
            String xml;
            Element mapper;
            try {
                xml = XmlSources.readString(file);
                mapper = XmlSources.parse(file).getDocumentElement();
            } catch (Exception e) {
                MapperStatementIndex.BrokenMapper broken = MapperStatementIndex.inspectBrokenFile(file);
                if (broken == MapperStatementIndex.BrokenMapper.UNKNOWN) {
                    // どの namespace の resultMap を使っているのかわからない。
                    return List.of();
                }
                if (broken != MapperStatementIndex.BrokenMapper.NOT_A_MAPPER) {
                    // 壊れたファイルが別の namespace の resultMap を完全修飾で使うことはまれなので、
                    // その namespace の resultMap だけを見ないことにする。
                    brokenNamespaces.add(broken.namespace());
                }
                continue;
            }
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                continue;
            }
            String namespace = mapper.getAttribute("namespace").trim();
            if (namespace.contains("${")) {
                return List.of();
            }
            if (namespace.isEmpty()) {
                // namespace のない mapper XML は MyBatis が読み込めない(missing-namespace で報告する)。
                // 自分の resultMap は宣言にならないが、完全修飾の参照は使われ方として数えておく。
                collectUsages(mapper, "", usages);
                continue;
            }
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element && "resultMap".equals(element.getTagName())) {
                    String id = qualify(element.getAttribute("id").trim(), namespace);
                    if (declarations.containsKey(id)) {
                        duplicated.add(id);
                    }
                    declarations.put(id, new Declaration(file, xml, element));
                }
            }
            collectUsages(mapper, namespace, usages);
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        declarations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<Usage> uses = usages.getOrDefault(entry.getKey(), List.of());
            String namespace = entry.getKey().substring(0, entry.getKey().lastIndexOf('.'));
            if (duplicated.contains(entry.getKey()) || brokenNamespaces.contains(namespace)
                    || uses.size() != 1 || uses.get(0).select() == null) {
                return;
            }
            check(entry.getValue(), uses.get(0).select(), displayPath, diagnostics);
        });
        return diagnostics;
    }

    private static void collectUsages(Element element, String namespace, Map<String, List<Usage>> usages) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            String extendsValue = child.getAttribute("extends").trim();
            if ("resultMap".equals(child.getTagName()) && !extendsValue.isEmpty()) {
                usages.computeIfAbsent(qualify(extendsValue, namespace), ignored -> new ArrayList<>())
                        .add(new Usage(null));
            }
            String resultMap = child.getAttribute("resultMap").trim();
            if (!resultMap.isEmpty()) {
                String[] ids = resultMap.split(",");
                boolean singleSelect = "select".equals(child.getTagName()) && ids.length == 1;
                for (String id : ids) {
                    // statement 以外(association など)や複数指定からの参照は、列を突き合わせられない。
                    boolean isStatement = STATEMENT_TAGS.contains(child.getTagName());
                    usages.computeIfAbsent(qualify(id.trim(), namespace), ignored -> new ArrayList<>())
                            .add(new Usage(singleSelect && isStatement ? child : null));
                }
            }
            collectUsages(child, namespace, usages);
        }
    }

    private static void check(Declaration declaration, Element select, Function<Path, Path> displayPath,
                              List<Diagnostic> diagnostics) {
        Element resultMap = declaration.resultMap();
        if (!resultMap.getAttribute("extends").isBlank()) {
            return;
        }
        List<Element> columns = new ArrayList<>();
        NodeList children = resultMap.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                if (!COLUMN_TAGS.contains(element.getTagName())) {
                    return;
                }
                columns.add(element);
            }
        }
        List<String> labels = MapperSelectAliasValidator.extractSelectLabels(MapperSelectAliasValidator.leadingSql(select));
        if (labels == null) {
            return;
        }
        Set<String> selected = new HashSet<>();
        labels.forEach(label -> selected.add(label.toUpperCase(Locale.ROOT)));

        String xml = declaration.xml();
        int cursor = findResultMapStart(xml, resultMap.getAttribute("id"));
        for (Element column : columns) {
            int elementStart = findTag(xml, column.getTagName(), cursor);
            if (elementStart >= 0) {
                cursor = elementStart + 1;
            }
            String name = column.getAttribute("column").trim();
            if (name.isEmpty() || name.contains("${") || name.contains("{") || name.contains(",")) {
                continue;
            }
            String unquoted = name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")
                    ? name.substring(1, name.length() - 1) : name;
            if (selected.contains(unquoted.toUpperCase(Locale.ROOT))) {
                continue;
            }
            Range range = elementStart < 0 ? Range.at(1, 1)
                    : XmlPositions.rangeForAttributeValue(xml, "column", column.getAttribute("column"), elementStart);
            String property = column.getAttribute("property");
            diagnostics.add(new Diagnostic(displayPath.apply(declaration.file()), declaration.file().toUri().toString(),
                    range, Severity.WARNING, RuleIds.RESULT_MAP_COLUMN_NOT_SELECTED,
                    "Column '" + name + "' of resultMap '" + resultMap.getAttribute("id")
                            + "' is not in the SELECT list of '" + select.getAttribute("id") + "'"
                            + (property.isBlank() ? "" : ", so property '" + property + "' stays null") + "."));
        }
    }

    private static String qualify(String id, String namespace) {
        // namespace がないときの短い id は、どの resultMap も指さない名前にする。
        return id.contains(".") ? id : (namespace.isEmpty() ? "" : namespace + ".") + id;
    }

    private static int findResultMapStart(String xml, String id) {
        Matcher matcher = Pattern.compile("<resultMap(?=[\\s>/])").matcher(xml);
        while (matcher.find()) {
            if (XmlPositions.findAttributeValueStart(xml, matcher.start(), "id", id) >= 0) {
                return matcher.start();
            }
        }
        return 0;
    }

    private static int findTag(String xml, String tag, int from) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tag) + "(?=[\\s>/])").matcher(xml);
        return matcher.find(from) ? matcher.start() : -1;
    }
}
