package com.mybatislint.analyzer.javaindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.mybatislint.analyzer.discovery.JavaFileScanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal workspace index of Java mapper interfaces and their methods.
 */
public final class JavaMapperIndex {

    private final Map<String, MapperInterfaceInfo> interfacesByQualifiedName = new HashMap<>();

    public static JavaMapperIndex empty() {
        return new JavaMapperIndex();
    }

    public static JavaMapperIndex build(Path workspaceRoot) {
        JavaMapperIndex index = new JavaMapperIndex();
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return index;
        }
        for (Path file : new JavaFileScanner().scan(workspaceRoot)) {
            index.indexJavaFile(file);
        }
        return index;
    }

    public MapperInterfaceInfo findInterface(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return null;
        }
        MapperInterfaceInfo direct = interfacesByQualifiedName.get(namespace);
        if (direct != null) {
            return direct;
        }
        for (MapperInterfaceInfo candidate : interfacesByQualifiedName.values()) {
            if (candidate.getQualifiedName().equals(namespace)
                    || candidate.getQualifiedName().endsWith("." + namespace)
                    || candidate.getSimpleName().equals(namespace)) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return interfacesByQualifiedName.isEmpty();
    }

    private void indexJavaFile(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return;
            }
            CompilationUnit unit = parseResult.getResult().get();
            String packageName = unit.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            for (var type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!type.isInterface()) {
                    continue;
                }
                String fqcn = qualifiedName(packageName, type);
                MapperInterfaceInfo info = interfacesByQualifiedName.computeIfAbsent(fqcn,
                        ignored -> new MapperInterfaceInfo(fqcn));
                for (MethodDeclaration method : type.getMethods()) {
                    MapperMethodInfo methodInfo = MapperMethodInfo.from(method);
                    if (methodInfo != null) {
                        info.addMethod(methodInfo);
                    }
                }
            }
        } catch (IOException e) {
            // A single unreadable Java source file should never block analysis of
            // the rest of the workspace; treat it as an empty index entry and move on.
            return;
        }
    }

    private static String qualifiedName(String packageName, ClassOrInterfaceDeclaration type) {
        String result = type.getFullyQualifiedName().map(fqn -> fqn.toString())
                .orElse((packageName.isBlank() ? "" : packageName + ".") + type.getNameAsString());
        if (packageName.isBlank()) {
            return result;
        }
        if (result.startsWith(packageName + ".")) {
            return result;
        }
        return packageName + "." + result;
    }

    public static final class MapperInterfaceInfo {
        private final String qualifiedName;
        private final Map<String, List<MapperMethodInfo>> methodsByName = new HashMap<>();

        private MapperInterfaceInfo(String qualifiedName) {
            this.qualifiedName = qualifiedName;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public String getSimpleName() {
            int dot = qualifiedName.lastIndexOf('.');
            return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
        }

        public void addMethod(MapperMethodInfo method) {
            methodsByName.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(method);
        }

        public MapperMethodInfo resolveMethod(String methodName) {
            List<MapperMethodInfo> methods = methodsByName.get(methodName);
            if (methods == null || methods.isEmpty()) {
                return null;
            }
            if (methods.size() > 1) {
                return null;
            }
            return methods.get(0);
        }
    }

    public static final class MapperMethodInfo {
        private final String name;
        private final List<String> parameterNames;
        private final List<String> parameterTypes;
        private final List<String> paramAliases;
        private final boolean mapLikeParameter;

        private MapperMethodInfo(String name,
                                 List<String> parameterNames,
                                 List<String> parameterTypes,
                                 List<String> paramAliases,
                                 boolean mapLikeParameter) {
            this.name = name;
            this.parameterNames = Collections.unmodifiableList(new ArrayList<>(parameterNames));
            this.parameterTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes));
            this.paramAliases = Collections.unmodifiableList(new ArrayList<>(paramAliases));
            this.mapLikeParameter = mapLikeParameter;
        }

        public static MapperMethodInfo from(MethodDeclaration method) {
            if (method.isStatic()) {
                return null;
            }
            List<String> paramNames = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            boolean mapLike = false;
            for (Parameter parameter : method.getParameters()) {
                String paramName = parameter.getNameAsString();
                String paramType = parameter.getTypeAsString();
                paramNames.add(paramName);
                paramTypes.add(paramType);
                if ("Map".equals(paramType) || paramType.endsWith(".Map") || paramType.contains("Map<")) {
                    mapLike = true;
                }
                String alias = findParamAlias(parameter);
                if (alias != null && !alias.isBlank()) {
                    aliases.add(alias);
                }
            }
            if (paramNames.isEmpty()) {
                return null;
            }
            return new MapperMethodInfo(method.getNameAsString(), paramNames, paramTypes, aliases, mapLike);
        }

        private static String findParamAlias(Parameter parameter) {
            for (var annotation : parameter.getAnnotations()) {
                String name = annotation.getNameAsString();
                if (!isParamAnnotation(name)) {
                    continue;
                }
                if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
                    Expression value = singleMember.getMemberValue();
                    if (value instanceof StringLiteralExpr literal) {
                        return literal.getValue();
                    }
                }
                for (var child : annotation.getChildNodes()) {
                    if (child instanceof MemberValuePair pair && "value".equals(pair.getNameAsString())) {
                        Expression value = pair.getValue();
                        if (value instanceof StringLiteralExpr literal) {
                            return literal.getValue();
                        }
                    }
                }
            }
            return null;
        }

        private static boolean isParamAnnotation(String annotationName) {
            String normalized = annotationName == null ? "" : annotationName.trim();
            if (normalized.isEmpty()) {
                return false;
            }
            if (normalized.endsWith(".Param") || normalized.equals("Param")) {
                return true;
            }
            return normalized.equals("org.apache.ibatis.annotations.Param");
        }

        public String getName() {
            return name;
        }

        public boolean hasMapLikeParameter() {
            return mapLikeParameter;
        }

        public Set<String> availableNames() {
            Set<String> names = new LinkedHashSet<>();
            names.add("_parameter");
            names.add("_databaseId");
            for (String alias : paramAliases) {
                if (!alias.isBlank()) {
                    names.add(alias);
                }
            }
            for (int index = 0; index < parameterNames.size(); index++) {
                String parameterName = parameterNames.get(index);
                if (parameterName != null && !parameterName.isBlank()) {
                    names.add(parameterName);
                }
                names.add("param" + (index + 1));
                names.add("arg" + index);
            }
            if (parameterNames.size() == 1) {
                names.add("param1");
                names.add("arg0");
            }
            return names;
        }
    }
}
