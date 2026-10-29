package com.mybatislint.analyzer.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ワークスペース内の {@code <mapper namespace="...">} と {@code <sql id="...">}
 * の対応を保持する索引。
 *
 * <p>MyBatis の {@code <include refid="...">} は、同じ mapper 内の短い ID
 * だけでなく、{@code com.example.OtherMapper.Columns} のような
 * 「namespace + '.' + sql id」の完全修飾名でも参照できる。単一ファイルを
 * 見ているだけでは他 mapper の {@code <sql>} を解決できないため、CLI が
 * 先にワークスペース全体を軽く走査してこの索引を作る設計にしている。</p>
 */
public final class MapperSqlFragmentIndex {

    private final Map<String, Set<String>> sqlIdsByNamespace;
    private final List<String> namespacesByLengthDesc;

    private MapperSqlFragmentIndex(Map<String, Set<String>> sqlIdsByNamespace) {
        this.sqlIdsByNamespace = sqlIdsByNamespace;
        this.namespacesByLengthDesc = new ArrayList<>(sqlIdsByNamespace.keySet());
        // namespace にはドットが含まれることが多い。最長一致にしておくと、
        // "com.example.UserMapper.BaseColumns" のような refid を
        // "com.example" で早く切ってしまう誤判定を避けられる。
        this.namespacesByLengthDesc.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }

    public static MapperSqlFragmentIndex empty() {
        return new MapperSqlFragmentIndex(Collections.emptyMap());
    }

    public static MapperSqlFragmentIndex build(List<Path> mapperFiles) {
        Map<String, Set<String>> sqlIdsByNamespace = new HashMap<>();
        for (Path mapperFile : mapperFiles) {
            addFile(mapperFile, sqlIdsByNamespace);
        }
        return new MapperSqlFragmentIndex(sqlIdsByNamespace);
    }

    public boolean contains(String namespace, String sqlId) {
        Set<String> sqlIds = sqlIdsByNamespace.get(namespace);
        return sqlIds != null && sqlIds.contains(sqlId);
    }

    /** この namespace の mapper がワークスペースにあるか(<sql> の有無は問わない)。 */
    public boolean containsNamespace(String namespace) {
        return sqlIdsByNamespace.containsKey(namespace);
    }

    public boolean containsQualifiedRefid(String refid) {
        if (refid == null || refid.isBlank()) {
            return false;
        }
        for (String namespace : namespacesByLengthDesc) {
            String prefix = namespace + ".";
            if (!refid.startsWith(prefix)) {
                continue;
            }
            String sqlId = refid.substring(prefix.length());
            if (!sqlId.isBlank() && contains(namespace, sqlId)) {
                return true;
            }
        }
        return false;
    }

    private static void addFile(Path mapperFile, Map<String, Set<String>> target) {
        try {
            Document document = XmlSources.parse(mapperFile);
            Element mapper = document.getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return;
            }
            String namespace = mapper.getAttribute("namespace");
            if (namespace == null || namespace.isBlank()) {
                return;
            }
            Set<String> sqlIds = target.computeIfAbsent(namespace, ignored -> new HashSet<>());
            for (Element sql : directSqlChildren(mapper)) {
                String id = sql.getAttribute("id");
                if (id != null && !id.isBlank()) {
                    sqlIds.add(id);
                }
            }
        } catch (Exception ignored) {
            // 不正 XML は通常の解析フェーズで mybatis-lint/malformed-xml として報告する。
            // 索引作成中に例外を広げると他ファイルの include 検証まで止まるため、
            // ここでは「索引に載せられなかった」とだけ扱うフェイルオープンにしている。
        }
    }

    private static List<Element> directSqlChildren(Element mapper) {
        List<Element> sqlChildren = new ArrayList<>();
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && "sql".equals(element.getTagName())) {
                sqlChildren.add(element);
            }
        }
        return sqlChildren;
    }
}
