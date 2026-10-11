package com.mybatislint.analyzer.xml;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Workspace index of explicitly declared, top-level resultMap IDs. */
public final class MapperResultMapIndex {

    private final Map<String, Integer> qualifiedIdCounts;

    private MapperResultMapIndex(Map<String, Integer> qualifiedIdCounts) {
        this.qualifiedIdCounts = Map.copyOf(qualifiedIdCounts);
    }

    public static MapperResultMapIndex empty() {
        return new MapperResultMapIndex(Map.of());
    }

    public static MapperResultMapIndex build(List<Path> mapperFiles) {
        Map<String, Integer> qualifiedIdCounts = new HashMap<>();
        for (Path file : mapperFiles) {
            try {
                Element mapper = SafeXmlParserFactory.newDocumentBuilder()
                        .parse(file.toFile()).getDocumentElement();
                if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                    continue;
                }
                String namespace = mapper.getAttribute("namespace");
                if (namespace.isBlank() || namespace.contains("${")) {
                    continue;
                }
                collectLocalIdCounts(mapper).forEach((id, count) ->
                        qualifiedIdCounts.merge(namespace + "." + id, count, Integer::sum));
            } catch (Exception ignored) {
                // Malformed/unreadable XML is reported during the normal analysis phase.
            }
        }
        return new MapperResultMapIndex(qualifiedIdCounts);
    }

    public boolean contains(String qualifiedId) {
        return qualifiedIdCounts.containsKey(qualifiedId);
    }

    public boolean isDuplicate(String qualifiedId) {
        return qualifiedIdCounts.getOrDefault(qualifiedId, 0) > 1;
    }

    public boolean containsShortId(String id) {
        return qualifiedIdCounts.keySet().stream().anyMatch(qualifiedId -> qualifiedId.endsWith("." + id));
    }

    static Set<String> collectLocalIds(Element mapper) {
        return collectLocalIdCounts(mapper).keySet();
    }

    static Map<String, Integer> collectLocalIdCounts(Element mapper) {
        Map<String, Integer> ids = new HashMap<>();
        String prefix = mapper.getAttribute("namespace") + ".";
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && "resultMap".equals(element.getTagName())) {
                String id = element.getAttribute("id");
                if (!id.isBlank() && !id.contains("${")) {
                    ids.merge(id.startsWith(prefix) ? id.substring(prefix.length()) : id, 1, Integer::sum);
                }
            }
        }
        return ids;
    }
}
