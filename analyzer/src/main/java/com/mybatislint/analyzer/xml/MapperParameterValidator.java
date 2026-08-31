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
                Set<String> allowedNames = method.availableNames();
                Set<String> statementScope = new LinkedHashSet<>(allowedNames);
                addScopedNames(statement, statementScope);
                validateIfAndWhenTests(statement, statementScope, method, xml, displayFile, uri, diagnostics);
                validateTemplateReferences(statement, statementScope, method, xml, displayFile, uri, diagnostics);
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
        int searchStart = 0;
        while (matcher.find()) {
            String raw = matcher.group();
            String expression = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (expression == null) {
                continue;
            }
            for (String root : ExpressionRootIdentifierExtractor.extractRoots(expression)) {
                if (allowedNames.contains(root)) {
                    continue;
                }
                if (root == null || root.isBlank()) {
                    continue;
                }
                if (root.startsWith("_")) {
                    continue;
                }
                diagnostics.add(diagnosticForExpression(xml, displayFile, uri, method, root,
                        raw, statement, searchStart));
            }
            searchStart = matcher.end();
        }
    }

    private static void validateIfAndWhenTests(Element statement,
                                               Set<String> allowedNames,
                                               MapperMethodInfo method,
                                               String xml,
                                               Path displayFile,
                                               String uri,
                                               List<Diagnostic> diagnostics) {
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
                if (allowedNames.contains(root)) {
                    continue;
                }
                if (root == null || root.isBlank()) {
                    continue;
                }
                if (root.startsWith("_")) {
                    continue;
                }
                diagnostics.add(diagnosticForExpression(xml, displayFile, uri, method, root,
                        expression, element, 0));
            }
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
                                                     Element element,
                                                     int searchOffset) {
        String snippet = rawSnippet;
        int positionIndex = xml.indexOf(snippet, searchOffset);
        if (positionIndex < 0) {
            positionIndex = xml.indexOf(root, findStartOfElement(xml, element));
        }
        if (positionIndex < 0) {
            positionIndex = findStartOfElement(xml, element);
        }
        Position pos = toPosition(xml, positionIndex);
        return new Diagnostic(displayFile, uri, Range.at(pos.getLine(), pos.getCharacter()), Severity.ERROR,
                RuleIds.UNDEFINED_PARAMETER,
                "Undefined parameter '" + root + "' in mapper method '" + method.getName() + "'.");
    }

    private static Diagnostic diagnosticForExpression(String xml,
                                                     Path displayFile,
                                                     String uri,
                                                     MapperMethodInfo method,
                                                     String root,
                                                     String rawSnippet,
                                                     Node statement,
                                                     int searchOffset) {
        return diagnosticForExpression(xml, displayFile, uri, method, root, rawSnippet, (Element) statement, searchOffset);
    }

    private static int findStartOfElement(String xml, Element element) {
        String tag = element.getTagName();
        String search = "<" + tag;
        int index = xml.indexOf(search);
        if (index < 0) {
            return 0;
        }
        return index;
    }

    private static Position toPosition(String xml, int index) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < index && i < xml.length(); i++) {
            if (xml.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }
}
