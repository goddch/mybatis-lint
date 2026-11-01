package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperMethodInfo;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashSet;
import java.util.Set;

/**
 * statement の中で {@code status} や {@code user.status} と書いた名前が指す値の Java の型を、
 * Mapper メソッドの引数と、引数の Bean のフィールドから決める。OGNL の式や {@code ${...}} の
 * 検証で、値の型によって判断を変えるために使う。
 */
final class ParameterTypeLookup {

    static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "long", "short", "byte", "double", "float",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger", "Number");
    // 引数 1 つで @Param がないとき、どの名前でも値そのものを指す型。
    private static final Set<String> VALUE_TYPES = Set.of(
            "int", "long", "short", "byte", "double", "float", "char", "boolean",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger", "Number",
            "Character", "Boolean", "String");

    private final JavaTypeIndex typeIndex;

    ParameterTypeLookup(JavaTypeIndex typeIndex) {
        this.typeIndex = typeIndex;
    }

    /** statement ごとの情報。Java の Mapper メソッドが決まらなければ method は null。 */
    record StatementContext(MapperMethodInfo method, Set<String> scopedNames) {

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

    /**
     * 名前が指す値の型の simple name。引数の型か、引数の Bean のフィールドの型から決める。
     * {@code <bind>} や {@code <foreach>} の変数と同じ名前や、決められない場合は null。
     */
    String typeOf(String operand, StatementContext context) {
        String[] parts = operand.split("\\.");
        if (parts.length > 2 || context.method() == null || context.scopedNames().contains(parts[0])) {
            return null;
        }
        MapperMethodInfo method = context.method();
        if (method.isSingleUnannotatedParameter()) {
            String parameterType = simpleName(method.getParameterTypes().get(0));
            if (parts.length == 1 && VALUE_TYPES.contains(parameterType)) {
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

    static String simpleName(String type) {
        String name = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        return name.trim().substring(name.trim().lastIndexOf('.') + 1);
    }
}
