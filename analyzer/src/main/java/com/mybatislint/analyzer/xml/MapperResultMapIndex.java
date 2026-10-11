package com.mybatislint.analyzer.xml;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Workspace index of explicitly declared, top-level resultMap IDs. */
public final class MapperResultMapIndex {

    private final Set<String> qualifiedIds;

    private MapperResultMapIndex(Set<String> qualifiedIds) {
        this.qualifiedIds = Set.copyOf(qualifiedIds);
    }

    public static MapperResultMapIndex empty() {
        return new MapperResultMapIndex(Set.of());
    }

    public static MapperResultMapIndex build(List<Path> mapperFiles) {
        Set<String> qualifiedIds = new HashSet<>();
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
                for (String id : collectLocalIds(mapper)) {
                    qualifiedIds.add(namespace + "." + id);
                }
            } catch (Exception ignored) {
                // Malformed/unreadable XML is reported during the normal analysis phase.
            }
        }
        return new MapperResultMapIndex(qualifiedIds);
    }

    public boolean contains(String qualifiedId) {
        return qualifiedIds.contains(qualifiedId);
    }

    static Set<String> collectLocalIds(Element mapper) {
        Set<String> ids = new HashSet<>();
        String prefix = mapper.getAttribute("namespace") + ".";
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && "resultMap".equals(element.getTagName())) {
                String id = element.getAttribute("id");
                if (!id.isBlank() && !id.contains("${")) {
                    ids.add(id.startsWith(prefix) ? id.substring(prefix.length()) : id);
                }
            }
        }
        return ids;
    }
}
