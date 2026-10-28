package com.mybatislint.analyzer.xml;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ワークスペースの mapper XML にある statement(select / insert / update / delete)の id を、
 * namespace ごとに集めた索引。同じ namespace の mapper が複数ファイルに分かれていてもまとめる。
 */
public final class MapperStatementIndex {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Pattern MAPPER_NAMESPACE =
            Pattern.compile("<mapper\\b[^>]*?\\bnamespace\\s*=\\s*[\"']([^\"']*)[\"']");

    /**
     * statement が宣言されている場所。
     *
     * @param rawId          XML に書かれたままの id(namespace 付きのこともある)
     * @param hasDatabaseId  databaseId 属性があるか
     */
    public record Occurrence(Path file, String rawId, boolean hasDatabaseId) {
    }

    private final Map<String, Set<String>> statementIdsByNamespace;
    // namespace → id → 宣言されている場所(ファイルの順)。
    private final Map<String, Map<String, List<Occurrence>>> occurrencesByNamespace;
    // 壊れていて statement を読めなかった mapper XML の namespace。
    private final Set<String> unreadableNamespaces;
    private final boolean complete;

    private MapperStatementIndex(Map<String, Set<String>> statementIdsByNamespace,
                                 Map<String, Map<String, List<Occurrence>>> occurrencesByNamespace,
                                 Set<String> unreadableNamespaces, boolean complete) {
        this.statementIdsByNamespace = statementIdsByNamespace;
        this.occurrencesByNamespace = occurrencesByNamespace;
        this.unreadableNamespaces = unreadableNamespaces;
        this.complete = complete;
    }

    public static MapperStatementIndex build(List<Path> xmlFiles) {
        Map<String, Set<String>> ids = new HashMap<>();
        Map<String, Map<String, List<Occurrence>>> occurrences = new HashMap<>();
        Set<String> unreadable = new HashSet<>();
        boolean complete = true;
        for (Path file : xmlFiles) {
            try {
                Element mapper = SafeXmlParserFactory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                    continue;
                }
                String namespace = mapper.getAttribute("namespace").trim();
                if (namespace.isEmpty() || namespace.contains("${")) {
                    // どの namespace の statement かわからないものがある。
                    complete = false;
                    continue;
                }
                Set<String> namespaceIds = ids.computeIfAbsent(namespace, ignored -> new HashSet<>());
                NodeList children = mapper.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child instanceof Element element && STATEMENT_TAGS.contains(element.getTagName())) {
                        String rawId = element.getAttribute("id").trim();
                        // MyBatis は namespace 付きの id も受け付ける。
                        String id = rawId.startsWith(namespace + ".") ? rawId.substring(namespace.length() + 1) : rawId;
                        namespaceIds.add(id);
                        if (!id.isEmpty() && !id.contains("${")) {
                            occurrences.computeIfAbsent(namespace, ignored -> new HashMap<>())
                                    .computeIfAbsent(id, ignored -> new ArrayList<>())
                                    .add(new Occurrence(file, rawId, !element.getAttribute("databaseId").isBlank()));
                        }
                    }
                }
            } catch (Exception e) {
                // 編集中などで壊れた mapper XML の statement は読めない。namespace だけでも
                // 読めればその namespace だけを判断できないものとし、読めなければ全体を諦める。
                // mapper XML でなければ関係ない。
                String text = readText(file);
                if (text == null) {
                    complete = false;
                } else if (text.contains("<mapper")) {
                    Matcher matcher = MAPPER_NAMESPACE.matcher(text);
                    if (matcher.find() && !matcher.group(1).isBlank() && !matcher.group(1).contains("${")) {
                        unreadable.add(matcher.group(1).trim());
                    } else {
                        complete = false;
                    }
                }
            }
        }
        return new MapperStatementIndex(ids, occurrences, unreadable, complete);
    }

    /**
     * namespace がわからない mapper XML がなかったか。あると、どの namespace の
     * statement が抜けているのかわからない。
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     * この namespace の mapper XML の statement の id。mapper XML がない、または
     * 読めない mapper XML がある namespace なら null。
     */
    public Set<String> findStatementIds(String namespace) {
        if (unreadableNamespaces.contains(namespace)) {
            return null;
        }
        return statementIdsByNamespace.get(namespace);
    }

    /** namespace ごとの、id ごとの宣言場所。 */
    public Map<String, Map<String, List<Occurrence>>> occurrences() {
        return occurrencesByNamespace;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
