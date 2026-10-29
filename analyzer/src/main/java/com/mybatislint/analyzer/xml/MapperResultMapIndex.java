package com.mybatislint.analyzer.xml;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Workspace index of explicitly declared, top-level resultMap IDs. */
public final class MapperResultMapIndex {

    private final Map<String, Integer> qualifiedIdCounts;
    private final Set<String> cyclicIds;

    private MapperResultMapIndex(Map<String, Integer> qualifiedIdCounts, Map<String, String> parents) {
        this.qualifiedIdCounts = Map.copyOf(qualifiedIdCounts);
        parents.entrySet().removeIf(entry -> isDuplicate(entry.getKey()) || isDuplicate(entry.getValue()));
        this.cyclicIds = findCyclicIds(parents);
    }

    public static MapperResultMapIndex empty() {
        return new MapperResultMapIndex(Map.of(), new HashMap<>());
    }

    public static MapperResultMapIndex build(List<Path> mapperFiles) {
        Map<String, Integer> qualifiedIdCounts = new HashMap<>();
        Map<String, String> parents = new HashMap<>();
        for (Path file : mapperFiles) {
            try {
                Element mapper = XmlSources.parse(file).getDocumentElement();
                if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                    continue;
                }
                String namespace = mapper.getAttribute("namespace");
                if (namespace.isBlank() || namespace.contains("${")) {
                    continue;
                }
                collectLocalIdCounts(mapper).forEach((id, count) ->
                        qualifiedIdCounts.merge(namespace + "." + id, count, Integer::sum));
                collectParents(mapper, parents);
            } catch (Exception ignored) {
                // Malformed/unreadable XML is reported during the normal analysis phase.
            }
        }
        return new MapperResultMapIndex(qualifiedIdCounts, parents);
    }

    static MapperResultMapIndex fromMapper(Element mapper) {
        Map<String, Integer> counts = new HashMap<>();
        collectLocalIdCounts(mapper).forEach((id, count) ->
                counts.put(mapper.getAttribute("namespace") + "." + id, count));
        Map<String, String> parents = new HashMap<>();
        collectParents(mapper, parents);
        return new MapperResultMapIndex(counts, parents);
    }

    public boolean isInInheritanceCycle(String qualifiedId) {
        return cyclicIds.contains(qualifiedId);
    }

    private static void collectParents(Element mapper, Map<String, String> parents) {
        String namespace = mapper.getAttribute("namespace");
        if (namespace.isBlank() || namespace.contains("${")) {
            return;
        }
        String prefix = namespace + ".";
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && "resultMap".equals(element.getTagName())) {
                String id = element.getAttribute("id");
                String parent = element.getAttribute("extends");
                if (!id.isBlank() && !id.contains("${") && !parent.isBlank() && !parent.contains("${")) {
                    parents.put(id.startsWith(prefix) ? id : prefix + id,
                            parent.contains(".") ? parent : prefix + parent);
                }
            }
        }
    }

    private static Set<String> findCyclicIds(Map<String, String> parents) {
        Set<String> processed = new HashSet<>();
        Set<String> cycles = new HashSet<>();
        for (String id : parents.keySet()) {
            Set<String> path = new LinkedHashSet<>();
            String current = id;
            while (current != null && !processed.contains(current)) {
                if (!path.add(current)) {
                    boolean inCycle = false;
                    for (String member : path) {
                        inCycle |= member.equals(current);
                        if (inCycle) {
                            cycles.add(member);
                        }
                    }
                    break;
                }
                current = parents.get(current);
            }
            processed.addAll(path);
        }
        return Set.copyOf(cycles);
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
