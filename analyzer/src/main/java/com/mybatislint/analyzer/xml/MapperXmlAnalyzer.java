package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a single mapper XML file and produces {@link Diagnostic}s for the
 * MVP rule set described in {@code docs/architecture.md}.
 *
 * <p>Only files whose root element is {@code <mapper>} are treated as
 * mapper files; any other well-formed XML file is silently ignored (no
 * diagnostics), since it is not in scope for this analyzer.
 */
public final class MapperXmlAnalyzer {

    private static final String MAPPER = "mapper";
    private static final String CHOOSE = "choose";
    private static final String WHEN = "when";
    private static final String OTHERWISE = "otherwise";
    private static final java.util.Set<String> STATEMENT_ELEMENTS =
            java.util.Set.of("select", "insert", "update", "delete");

    /**
     * @param actualFile the path to read from disk (may be absolute)
     * @param displayFile the path to record on emitted diagnostics (e.g. a
     *                    path relative to the workspace root)
     * @param uri a stable {@code file://} URI identifying the file
     */
    public List<Diagnostic> analyze(Path actualFile, Path displayFile, String uri) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        SAXParser parser = SafeXmlParserFactory.newSaxParser();
        Handler handler = new Handler(displayFile, uri, diagnostics);
        try (InputStream in = java.nio.file.Files.newInputStream(actualFile)) {
            InputSource source = new InputSource(in);
            source.setSystemId(actualFile.toUri().toString());
            parser.parse(source, handler);
        } catch (SAXParseException e) {
            diagnostics.add(malformedXmlDiagnostic(displayFile, uri, e));
        } catch (SAXException e) {
            diagnostics.add(new Diagnostic(displayFile, uri, Range.at(1, 1), Severity.ERROR,
                    RuleIds.MALFORMED_XML, "Malformed XML: " + e.getMessage()));
        } catch (IOException e) {
            diagnostics.add(new Diagnostic(displayFile, uri, Range.at(1, 1), Severity.ERROR,
                    RuleIds.MALFORMED_XML, "Unable to read file: " + e.getMessage()));
        }
        return diagnostics;
    }

    private static Diagnostic malformedXmlDiagnostic(Path file, String uri, SAXParseException e) {
        int line = Math.max(e.getLineNumber(), 1);
        int column = Math.max(e.getColumnNumber(), 1);
        return new Diagnostic(file, uri, Range.at(line, column), Severity.ERROR,
                RuleIds.MALFORMED_XML, "Malformed XML: " + e.getMessage());
    }

    /** Tracking context for one element on the ancestor stack. */
    private static final class ElementContext {
        final String name;
        final int line;
        final int column;
        final Map<String, Integer> statementIdOccurrences = new HashMap<>();
        int otherwiseCount = 0;

        ElementContext(String name, int line, int column) {
            this.name = name;
            this.line = line;
            this.column = column;
        }
    }

    private static final class Handler extends DefaultHandler {
        private final Path file;
        private final String uri;
        private final List<Diagnostic> diagnostics;
        private final Deque<ElementContext> stack = new ArrayDeque<>();
        private Locator locator;
        private boolean sawMapperRoot = false;

        Handler(Path file, String uri, List<Diagnostic> diagnostics) {
            this.file = file;
            this.uri = uri;
            this.diagnostics = diagnostics;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uriNs, String localName, String qName, Attributes attributes) {
            int line = locator == null ? 1 : locator.getLineNumber();
            int column = locator == null ? 1 : locator.getColumnNumber();
            ElementContext parent = stack.peek();

            if (parent == null) {
                sawMapperRoot = MAPPER.equals(qName);
                if (sawMapperRoot) {
                    String namespace = attributes.getValue("namespace");
                    if (namespace == null || namespace.isBlank()) {
                        diagnostics.add(new Diagnostic(file, uri, Range.at(line, column), Severity.ERROR,
                                RuleIds.MISSING_NAMESPACE,
                                "<mapper> element is missing a non-blank 'namespace' attribute."));
                    }
                }
            } else if (sawMapperRoot) {
                if (MAPPER.equals(parent.name) && STATEMENT_ELEMENTS.contains(qName)) {
                    String id = attributes.getValue("id");
                    if (id != null && !id.isBlank()) {
                        Integer previousCount = parent.statementIdOccurrences.get(id);
                        if (previousCount != null) {
                            diagnostics.add(new Diagnostic(file, uri, Range.at(line, column), Severity.ERROR,
                                    RuleIds.DUPLICATE_STATEMENT_ID,
                                    "Duplicate statement id '" + id + "' in this mapper file."));
                        }
                        parent.statementIdOccurrences.merge(id, 1, Integer::sum);
                    }
                } else if (WHEN.equals(qName)) {
                    if (!CHOOSE.equals(parent.name)) {
                        diagnostics.add(new Diagnostic(file, uri, Range.at(line, column), Severity.ERROR,
                                RuleIds.WHEN_OUTSIDE_CHOOSE,
                                "<when> must be a direct child of <choose>."));
                    }
                } else if (OTHERWISE.equals(qName)) {
                    if (!CHOOSE.equals(parent.name)) {
                        diagnostics.add(new Diagnostic(file, uri, Range.at(line, column), Severity.ERROR,
                                RuleIds.OTHERWISE_OUTSIDE_CHOOSE,
                                "<otherwise> must be a direct child of <choose>."));
                    } else {
                        parent.otherwiseCount++;
                        if (parent.otherwiseCount > 1) {
                            diagnostics.add(new Diagnostic(file, uri, Range.at(line, column), Severity.ERROR,
                                    RuleIds.DUPLICATE_OTHERWISE,
                                    "A <choose> element must contain at most one <otherwise> child."));
                        }
                    }
                }
            }

            stack.push(new ElementContext(qName, line, column));
        }

        @Override
        public void endElement(String uriNs, String localName, String qName) {
            if (!stack.isEmpty()) {
                stack.pop();
            }
        }
    }
}
