package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperMethodInfo;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <if test>} / {@code <when test>} の OGNL 式で、MyBatis のよくある落とし穴を見つける。
 *
 * <h2>{@code mybatis-lint/single-char-literal-comparison}(warning)</h2>
 * <p>OGNL では {@code 'A'} のような 1 文字の単引用符は文字(char)になる。String の
 * {@code "A"} とは等しくならないので、{@code type == 'A'} は常に false になる。
 * 比べる相手が char / Character だとわかる場合だけは正しいので警告しない。</p>
 *
 * <h2>{@code mybatis-lint/numeric-empty-string-comparison}(warning)</h2>
 * <p>OGNL では数値と {@code ''} を比べると {@code ''} が 0 として扱われる。そのため
 * {@code status != null and status != ''} は status が 0 のときにも false になる。
 * 比べる相手が数値型だとわかる場合だけ警告する。</p>
 */
public final class MapperTestExpressionValidator {

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> CHAR_TYPES = Set.of("char", "Character");
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "long", "short", "byte", "double", "float",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger", "Number");

    private static final String OPERAND = "([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)";
    private static final String OPERATOR = "(==|!=|\\beq\\b|\\bneq\\b)";
    // 'A'.toString() と書けば String になるので、後ろに . が続くものは除く。
    private static final Pattern CHAR_RIGHT =
            Pattern.compile(OPERAND + "\\s*" + OPERATOR + "\\s*'([^'\\\\])'(?!\\s*\\.)");
    private static final Pattern CHAR_LEFT = Pattern.compile("'([^'\\\\])'\\s*" + OPERATOR + "\\s*" + OPERAND);
    private static final Pattern CHAR_EQUALS = Pattern.compile(OPERAND + "\\.equals(?:IgnoreCase)?\\(\\s*'([^'\\\\])'\\s*\\)");
    private static final Pattern EMPTY_RIGHT = Pattern.compile(OPERAND + "\\s*" + OPERATOR + "\\s*''");
    private static final Pattern EMPTY_LEFT = Pattern.compile("''\\s*" + OPERATOR + "\\s*" + OPERAND);

    private final JavaMapperIndex javaIndex;
    private final JavaTypeIndex typeIndex;

    public MapperTestExpressionValidator(JavaMapperIndex javaIndex, JavaTypeIndex typeIndex) {
        this.javaIndex = javaIndex == null ? JavaMapperIndex.empty() : javaIndex;
        this.typeIndex = typeIndex == null ? JavaTypeIndex.empty() : typeIndex;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            MapperInterfaceInfo mapperInterface = javaIndex.findInterface(mapper.getAttribute("namespace"));
            List<Diagnostic> diagnostics = new ArrayList<>();
            // DOM では位置がわからないので、要素を文書の順にたどりながら開始タグを順に探す。
            int[] cursor = {0};
            walk(mapper, null, mapperInterface, xml, cursor, displayFile, uri, diagnostics);
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    private void walk(Element element, StatementContext context, MapperInterfaceInfo mapperInterface, String xml,
                      int[] cursor, Path displayFile, String uri, List<Diagnostic> diagnostics) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            int start = findStartTag(xml, child.getTagName(), cursor[0]);
            if (start >= 0) {
                cursor[0] = start + 1;
            }
            StatementContext childContext = context;
            if (context == null && STATEMENT_TAGS.contains(child.getTagName())) {
                childContext = StatementContext.of(child, mapperInterface);
            }
            if (childContext != null && ("if".equals(child.getTagName()) || "when".equals(child.getTagName()))) {
                String test = child.getAttribute("test");
                if (!test.isBlank()) {
                    validateTest(test, childContext, xml, start, displayFile, uri, diagnostics);
                }
            }
            walk(child, childContext, mapperInterface, xml, cursor, displayFile, uri, diagnostics);
        }
    }

    private void validateTest(String test, StatementContext context, String xml, int elementStart,
                              Path displayFile, String uri, List<Diagnostic> diagnostics) {
        List<Finding> findings = new ArrayList<>();
        for (Pattern pattern : List.of(CHAR_RIGHT, CHAR_EQUALS)) {
            Matcher matcher = pattern.matcher(test);
            while (matcher.find()) {
                String literal = "'" + matcher.group(matcher.groupCount()) + "'";
                if (!isOneOf(CHAR_TYPES, operandType(matcher.group(1), context))) {
                    findings.add(new Finding(literal, true));
                }
            }
        }
        Matcher left = CHAR_LEFT.matcher(test);
        while (left.find()) {
            String literal = "'" + left.group(1) + "'";
            if (!isOneOf(CHAR_TYPES, operandType(left.group(3), context))) {
                findings.add(new Finding(literal, true));
            }
        }
        for (Pattern pattern : List.of(EMPTY_RIGHT, EMPTY_LEFT)) {
            Matcher matcher = pattern.matcher(test);
            while (matcher.find()) {
                String operand = pattern == EMPTY_RIGHT ? matcher.group(1) : matcher.group(2);
                if (isOneOf(NUMERIC_TYPES, operandType(operand, context))) {
                    findings.add(new Finding(matcher.group(), false));
                }
            }
        }
        for (Finding finding : findings) {
            diagnostics.add(diagnostic(finding, test, xml, elementStart, displayFile, uri));
        }
    }

    /** {@code type == 'A'} の 'A' か、{@code status != ''} の式全体。 */
    private record Finding(String text, boolean charLiteral) {
    }

    private static Diagnostic diagnostic(Finding finding, String test, String xml, int elementStart,
                                         Path displayFile, String uri) {
        Range range = XmlPositions.range(xml, Math.max(elementStart, 0), Math.max(elementStart, 0));
        if (elementStart >= 0) {
            int valueStart = XmlPositions.findAttributeValueStart(xml, elementStart, "test", test);
            int index = valueStart < 0 ? -1 : xml.indexOf(finding.text(), valueStart);
            if (index >= 0 && index < valueStart + test.length()) {
                range = XmlPositions.range(xml, index, index + finding.text().length());
            }
        }
        if (finding.charLiteral()) {
            String quoted = finding.text().substring(1, finding.text().length() - 1);
            return new Diagnostic(displayFile, uri, range, Severity.WARNING, RuleIds.SINGLE_CHAR_LITERAL_COMPARISON,
                    "OGNL treats " + finding.text() + " as a char, which never equals a String; "
                            + "write \"" + quoted + "\" (double quotes) or " + finding.text() + ".toString().");
        }
        return new Diagnostic(displayFile, uri, range, Severity.WARNING, RuleIds.NUMERIC_EMPTY_STRING_COMPARISON,
                "Comparing a number with '' treats '' as 0 in OGNL, so '" + finding.text()
                        + "' is also false when the value is 0; remove the '' comparison.");
    }

    /**
     * 比べる相手の型の simple name。引数の型か、引数の Bean のフィールドの型から決める。
     * {@code <bind>} や {@code <foreach>} の変数と同じ名前や、決められない場合は null。
     */
    private String operandType(String operand, StatementContext context) {
        String[] parts = operand.split("\\.");
        if (parts.length > 2 || context.method() == null || context.scopedNames().contains(parts[0])) {
            return null;
        }
        MapperMethodInfo method = context.method();
        if (method.isSingleUnannotatedParameter()) {
            String parameterType = simpleName(method.getParameterTypes().get(0));
            if (parts.length == 1 && (NUMERIC_TYPES.contains(parameterType) || CHAR_TYPES.contains(parameterType))) {
                // 単純型の引数 1 つなら、どの名前でも値そのものを指す。
                return parameterType;
            }
            String qualifiedName = typeIndex.resolveJavaTypeName(method.getParameterTypes().get(0));
            return parts.length == 1 && qualifiedName != null ? typeIndex.findFieldTypeName(qualifiedName, parts[0]) : null;
        }
        String parameterType = method.namedParameterTypes().get(parts[0]);
        if (parameterType == null) {
            return null;
        }
        if (parts.length == 1) {
            return simpleName(parameterType);
        }
        String qualifiedName = typeIndex.resolveJavaTypeName(parameterType);
        return qualifiedName == null ? null : typeIndex.findFieldTypeName(qualifiedName, parts[1]);
    }

    // Set.of の contains は null を渡すと例外になる。型がわからない(null)ときは false にする。
    private static boolean isOneOf(Set<String> types, String type) {
        return type != null && types.contains(type);
    }

    private static String simpleName(String type) {
        String name = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        return name.trim().substring(name.trim().lastIndexOf('.') + 1);
    }

    private static int findStartTag(String xml, String tag, int from) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tag) + "(?=[\\s>/])").matcher(xml);
        return matcher.find(from) ? matcher.start() : -1;
    }

    /** statement ごとの情報。Java の Mapper メソッドが決まらなければ method は null。 */
    private record StatementContext(MapperMethodInfo method, Set<String> scopedNames) {

        static StatementContext of(Element statement, MapperInterfaceInfo mapperInterface) {
            MapperMethodInfo method = mapperInterface == null ? null
                    : mapperInterface.resolveMethod(statement.getAttribute("id"));
            Set<String> scoped = new HashSet<>();
            collectScopedNames(statement, scoped);
            return new StatementContext(method, scoped);
        }

        private static void collectScopedNames(Element element, Set<String> scoped) {
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element child)) {
                    continue;
                }
                if ("bind".equals(child.getTagName())) {
                    scoped.add(child.getAttribute("name"));
                } else if ("foreach".equals(child.getTagName())) {
                    scoped.add(child.getAttribute("item"));
                    scoped.add(child.getAttribute("index"));
                }
                collectScopedNames(child, scoped);
            }
        }
    }
}
