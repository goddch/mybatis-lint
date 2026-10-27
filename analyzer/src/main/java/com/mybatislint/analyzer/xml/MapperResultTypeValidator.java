package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperMethodInfo;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code <select resultType="...">} と、一意に解決できた Java Mapper メソッドの
 * 戻り値型を突き合わせる検証器({@code mybatis-lint/result-type-mismatch})。
 *
 * <h2>なぜこれだけ狭いスコープにしたか</h2>
 *
 * <p>{@code parameterType} 側({@link MapperParameterTypeValidator})と違い、
 * resultType は {@code List<T>}/{@code Map}/{@code Cursor}/nested generics など
 * MyBatis 特有の表現の幅が広く、誤検知のリスクが高い。そのため、
 * 「Java 側の戻り値が単一オブジェクト型、要素がシンプルな {@code List<T>}、
 * またはシンプルな {@code Map<K, V>}」という最も安全に断定できるケースだけに
 * 絞り、それ以外はすべてフェイルオープンする。{@link MapperParameterTypeValidator}
 * と設計は似ているが、比較対象(引数 vs 戻り値)も判定できる範囲も異なるため、
 * 無理に共通化はしていない。</p>
 *
 * <h2>親子関係のある型</h2>
 *
 * <p>戻り値が親クラスやインターフェースで、resultType にその子クラスを書くのは正しい。
 * そのため名前が違うだけでは警告せず、ワークスペースのソースから親子関係をたどって
 * 「代入できないと確実に言える」場合だけ警告する。ワークスペース外の型は親子関係が
 * わからないので、判断できない組み合わせはフェイルオープンする。</p>
 */
public final class MapperResultTypeValidator {

    private static final Map<String, Set<String>> BUILTIN_ALIAS_JAVA_TYPES = Map.ofEntries(
            Map.entry("map", Set.of("Map")),
            Map.entry("string", Set.of("String")),
            Map.entry("byte", Set.of("byte", "Byte")),
            Map.entry("_byte", Set.of("byte", "Byte")),
            Map.entry("long", Set.of("long", "Long")),
            Map.entry("_long", Set.of("long", "Long")),
            Map.entry("short", Set.of("short", "Short")),
            Map.entry("_short", Set.of("short", "Short")),
            Map.entry("int", Set.of("int", "Integer")),
            Map.entry("_int", Set.of("int", "Integer")),
            Map.entry("integer", Set.of("int", "Integer")),
            Map.entry("_integer", Set.of("int", "Integer")),
            Map.entry("double", Set.of("double", "Double")),
            Map.entry("_double", Set.of("double", "Double")),
            Map.entry("float", Set.of("float", "Float")),
            Map.entry("_float", Set.of("float", "Float")),
            Map.entry("boolean", Set.of("boolean", "Boolean")),
            Map.entry("_boolean", Set.of("boolean", "Boolean")),
            Map.entry("char", Set.of("char", "Character")),
            Map.entry("_char", Set.of("char", "Character")),
            Map.entry("character", Set.of("char", "Character")),
            Map.entry("date", Set.of("Date")),
            Map.entry("decimal", Set.of("BigDecimal")),
            Map.entry("bigdecimal", Set.of("BigDecimal")),
            Map.entry("biginteger", Set.of("BigInteger")));

    // 互いに親子関係がないとわかっている JDK の型。戻り値型と resultType の両方が
    // ここに含まれ、名前が違えば代入できない(Map だけは実装クラスを別に扱う)。
    private static final Set<String> UNRELATED_JDK_TYPES = Set.of(
            "String", "Byte", "Long", "Short", "Integer", "Double", "Float", "Boolean", "Character",
            "Date", "BigDecimal", "BigInteger", "Map");

    private final JavaMapperIndex index;
    private final JavaTypeIndex typeIndex;

    public MapperResultTypeValidator(JavaMapperIndex index) {
        this(index, JavaTypeIndex.empty());
    }

    public MapperResultTypeValidator(JavaMapperIndex index, JavaTypeIndex typeIndex) {
        this.index = index == null ? JavaMapperIndex.empty() : index;
        this.typeIndex = typeIndex == null ? JavaTypeIndex.empty() : typeIndex;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        if (index.isEmpty()) {
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
                // parameterType 側と同じ理由でフェイルオープン: 対応する Java
                // Mapper が見つからないことと、不一致があることは別の意味を持つ。
                return Collections.emptyList();
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            int searchOffset = 0;
            for (Element select : collectSelectStatements(mapper)) {
                int elementStart = findStartOfElement(xml, select, searchOffset);
                searchOffset = elementStart + "<select".length();

                // resultMap を持つ statement は対象外(要件どおり)。
                String resultMap = select.getAttribute("resultMap");
                if (resultMap != null && !resultMap.isBlank()) {
                    continue;
                }

                String resultType = select.getAttribute("resultType");
                if (resultType == null || resultType.isBlank()) {
                    continue;
                }
                String trimmedXmlType = resultType.trim();
                if (trimmedXmlType.contains("${")) {
                    // typeAlias/parameterType 検証と同じ理由: 実行時のプロパティ
                    // 置換で値が変わるため静的には判断できない。
                    continue;
                }

                String methodId = select.getAttribute("id");
                if (methodId == null || methodId.isBlank()) {
                    continue;
                }
                // オーバーロードなどで一意に決まらない場合は null。曖昧な状態から
                // 決め打ちすると誤検知の原因になるためスキップする。
                MapperMethodInfo method = mapperInterface.resolveMethod(methodId);
                if (method == null) {
                    continue;
                }

                ReturnShape javaShape = ReturnShape.parse(method.getReturnType());
                if (javaShape == null) {
                    // void/Cursor/nested generics/array/型変数など、安全に
                    // 断定できない戻り値型はすべてフェイルオープンする。
                    continue;
                }

                if (!matches(trimmedXmlType, javaShape, typeIndex)) {
                    diagnostics.add(new Diagnostic(displayFile, uri,
                            rangeForAttributeValue(xml, select, "resultType", resultType, elementStart),
                            Severity.WARNING,
                            RuleIds.RESULT_TYPE_MISMATCH,
                            "Statement resultType '" + resultType + "' does not match the return type '"
                                    + method.getReturnType() + "' of mapper method '" + method.getName() + "'."));
                }
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方自体は MapperXmlAnalyzer の SAX パースが既に診断済みのはず。
            return Collections.emptyList();
        }
    }

    /**
     * XML 側 resultType が、Java 側の戻り値の要素型に代入できる可能性があるか。
     * 「確実に代入できない」と言えるときだけ false を返す。
     */
    private static boolean matches(String xmlValue, ReturnShape javaShape, JavaTypeIndex typeIndex) {
        String returnName = boxedName(javaShape.elementSimpleName);
        // Object(や List<Object>)はどんな resultType の結果でも受け取れる。
        if ("Object".equals(returnName)) {
            return true;
        }
        Set<String> xmlNames;
        Set<String> aliasTypes = BUILTIN_ALIAS_JAVA_TYPES.get(xmlValue.toLowerCase(Locale.ROOT));
        if (aliasTypes != null) {
            xmlNames = new HashSet<>();
            aliasTypes.forEach(name -> xmlNames.add(boxedName(name)));
        } else {
            // 未知の小文字 alias は個別登録の可能性を否定できない。
            if (!Character.isUpperCase(xmlValue.charAt(0))) {
                return true;
            }
            String xmlSimpleName = xmlValue.contains(".")
                    ? xmlValue.substring(xmlValue.lastIndexOf('.') + 1)
                    : xmlValue;
            // Map を返すメソッドに HashMap / LinkedHashMap などの実装クラスを指定するのは正しい。
            if ("Map".equals(returnName) && xmlSimpleName.endsWith("Map")) {
                return true;
            }
            xmlNames = Set.of(boxedName(xmlSimpleName));
        }
        if (xmlNames.contains(returnName)) {
            return true;
        }

        if (typeIndex.declaresType(returnName)) {
            // 戻り値がワークスペースの型なら、ワークスペース外の型(JDK やライブラリ)が
            // その子になることはない。ワークスペース内の親子関係だけをたどれば判断できる。
            for (String xmlName : xmlNames) {
                if (aliasTypes == null && reachesSupertype(xmlName, returnName, typeIndex, true)) {
                    return true;
                }
            }
            return false;
        }
        // ここから先は戻り値がワークスペース外の型。
        boolean xmlIsWorkspaceType = aliasTypes == null && typeIndex.declaresType(xmlNames.iterator().next());
        if (!xmlIsWorkspaceType) {
            // 両方ワークスペース外なら、親子関係がないとわかっている JDK の型同士だけ判断する。
            return !(UNRELATED_JDK_TYPES.contains(returnName) && UNRELATED_JDK_TYPES.containsAll(xmlNames));
        }
        // resultType がワークスペースの型なら、親をたどった先がすべてワークスペース内で
        // 戻り値型に行き着かない場合だけ、代入できないと言える。
        return reachesSupertype(xmlNames.iterator().next(), returnName, typeIndex, false);
    }

    /**
     * {@code start} から親クラス/インターフェースをたどって {@code target} に行き着くか。
     * 行き着かないと断定できない場合(一意に決まらない型や、たどれない型がある場合)も
     * true を返して警告を避ける。
     *
     * @param ignoreExternalTypes ワークスペース外の型をたどれなくても無視してよいか。
     *                            target がワークスペースの型なら、外部の型はその子になりえない
     */
    private static boolean reachesSupertype(String start, String target, JavaTypeIndex typeIndex,
                                            boolean ignoreExternalTypes) {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (name.equals(target)) {
                return true;
            }
            if (!visited.add(name)) {
                continue;
            }
            if (!typeIndex.declaresType(name)) {
                if (ignoreExternalTypes) {
                    continue;
                }
                return true;
            }
            Set<String> supertypes = typeIndex.findDirectSupertypes(name);
            if (supertypes == null) {
                // 同じ simple name の型が複数あり、どれの親をたどればよいか決められない。
                return true;
            }
            pending.addAll(supertypes);
        }
        return false;
    }

    private static String boxedName(String simpleName) {
        return switch (simpleName) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "double" -> "Double";
            case "float" -> "Float";
            case "boolean" -> "Boolean";
            case "char" -> "Character";
            default -> simpleName;
        };
    }

    /** Java 側の戻り値型のうち、今回比較対象にする「単一オブジェクト or 単純な List<T>」の形。 */
    private static final class ReturnShape {
        private final String elementSimpleName;

        private ReturnShape(String elementSimpleName) {
            this.elementSimpleName = elementSimpleName;
        }

        static ReturnShape parse(String rawType) {
            if (rawType == null || rawType.isBlank()) {
                return null;
            }
            String type = rawType.trim();
            if (type.equals("void") || type.endsWith("[]") || type.contains("?")) {
                return null;
            }
            int genericStart = type.indexOf('<');
            if (genericStart < 0) {
                return elementShape(type);
            }
            if (!type.endsWith(">")) {
                return null;
            }
            String outer = type.substring(0, genericStart).trim();
            String outerSimple = simpleNameOf(outer);
            if ("Map".equals(outerSimple)) {
                String mapTypeArgs = type.substring(genericStart + 1, type.length() - 1).trim();
                if (mapTypeArgs.contains("<") || mapTypeArgs.contains(">")) {
                    // Map<String, List<User>> のような nested generics は対象外。
                    return null;
                }
                String[] args = mapTypeArgs.split(",", -1);
                if (args.length != 2 || args[0].trim().isEmpty() || args[1].trim().isEmpty()) {
                    // Map<K, V> の形に限定する(欠落した型引数や片側のみは対象外)。
                    return null;
                }
                return elementShape(outerSimple);
            }
            if (!"List".equals(outerSimple)) {
                // Cursor<T>/Set<T> など、List/Map 以外のジェネリクスは対象外。
                return null;
            }
            String inner = type.substring(genericStart + 1, type.length() - 1).trim();
            if (inner.isEmpty() || inner.contains("<") || inner.contains(">") || inner.contains(",")) {
                // nested generics や複数型引数は対象外。
                return null;
            }
            return elementShape(inner);
        }

        private static ReturnShape elementShape(String type) {
            String simple = simpleNameOf(type);
            if (simple.isEmpty() || "Cursor".equals(simple)) {
                return null;
            }
            if (looksLikeGenericTypeVariable(simple)) {
                return null;
            }
            return new ReturnShape(simple);
        }

        private static String simpleNameOf(String type) {
            return type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
        }

        private static boolean looksLikeGenericTypeVariable(String simpleName) {
            if (simpleName.length() > 2) {
                return false;
            }
            for (int i = 0; i < simpleName.length(); i++) {
                if (!Character.isUpperCase(simpleName.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static List<Element> collectSelectStatements(Element mapper) {
        List<Element> selects = new ArrayList<>();
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if ("select".equals(element.getTagName())) {
                selects.add(element);
                continue;
            }
            selects.addAll(collectSelectStatements(element));
        }
        return selects;
    }

    private static int findStartOfElement(String xml, Element element, int lowerBound) {
        String tag = element.getTagName();
        String search = "<" + tag;
        int index = xml.indexOf(search, lowerBound);
        while (index >= 0) {
            int afterName = index + search.length();
            if (afterName >= xml.length() || !XmlPositions.isXmlNameChar(xml.charAt(afterName))) {
                return index;
            }
            index = xml.indexOf(search, afterName);
        }
        return lowerBound;
    }

    private static Range rangeForAttributeValue(String xml,
                                                Element element,
                                                String attributeName,
                                                String attributeValue,
                                                int elementStart) {
        return XmlPositions.rangeForAttributeValue(xml, attributeName, attributeValue, elementStart);
    }





}
