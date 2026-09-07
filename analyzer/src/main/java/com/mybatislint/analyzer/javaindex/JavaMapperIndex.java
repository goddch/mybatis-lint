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
        private final String returnType;
        private final List<String> parameterNames;
        private final List<String> parameterTypes;
        private final List<String> paramAliases;
        private final boolean mapLikeParameter;
        // 単一引数が可変長引数(vararg, 例: `User... users`)かどうか。
        // vararg は実質「配列」であり、typeAsString() は "User" のように
        // 要素型だけを返してしまうため、そのまま simple name 比較すると
        // 「User という単一オブジェクト」と誤認して誤検知(false positive)を
        // 生みかねない。parameterType 突き合わせ側ではこのフラグを見て
        // 素直にフェイルオープンする。
        private final boolean singleParameterIsVarArgs;

        private MapperMethodInfo(String name,
                                 String returnType,
                                 List<String> parameterNames,
                                 List<String> parameterTypes,
                                 List<String> paramAliases,
                                 boolean mapLikeParameter,
                                 boolean singleParameterIsVarArgs) {
            this.name = name;
            this.returnType = returnType;
            this.parameterNames = Collections.unmodifiableList(new ArrayList<>(parameterNames));
            this.parameterTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes));
            this.paramAliases = Collections.unmodifiableList(new ArrayList<>(paramAliases));
            this.mapLikeParameter = mapLikeParameter;
            this.singleParameterIsVarArgs = singleParameterIsVarArgs;
        }

        public static MapperMethodInfo from(MethodDeclaration method) {
            if (method.isStatic()) {
                return null;
            }
            List<String> paramNames = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            boolean mapLike = false;
            boolean varArgs = false;
            for (Parameter parameter : method.getParameters()) {
                String paramName = parameter.getNameAsString();
                String paramType = parameter.getTypeAsString();
                paramNames.add(paramName);
                paramTypes.add(paramType);
                if ("Map".equals(paramType) || paramType.endsWith(".Map") || paramType.contains("Map<")) {
                    mapLike = true;
                }
                if (parameter.isVarArgs()) {
                    varArgs = true;
                }
                String alias = findParamAlias(parameter);
                if (alias != null && !alias.isBlank()) {
                    aliases.add(alias);
                }
            }
            // 従来はパラメータ 0 個のメソッドをインデックス対象外にしていたが、
            // `findAll()` のような引数なし select も resultType 突き合わせの
            // 対象にしたいため、戻り値型を追加した際にこの制限を撤廃した。
            String returnType = method.getType().asString();
            return new MapperMethodInfo(method.getNameAsString(), returnType, paramNames, paramTypes, aliases,
                    mapLike, varArgs);
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

        /**
         * 戻り値の宣言型を、ソースコードに書かれたそのままの文字列で返す
         * (例: {@code "User"}, {@code "List<User>"}, {@code "void"})。
         * {@code getParameterTypes()} と同様、シンボル解決はしていないので
         * 見た目だけの文字列である点に注意(呼び出し側で確信を持てる範囲だけ比較する)。
         * 値は {@code MethodDeclaration.getType().asString()} が返すソース上の
         * 見た目そのものであり、どんな型宣言でも例外を投げず文字列として返る
         * (JavaParser は構文解析のみでシンボル解決はしないため)。ただし
         * 配列/nested generics/型変数などパース結果を安全に解釈できない場合は
         * 呼び出し側(例: {@code MapperResultTypeValidator}) が判断してフェイルオープンする。
         */
        public String getReturnType() {
            return returnType;
        }

        public boolean hasMapLikeParameter() {
            return mapLikeParameter;
        }

        public int getParameterCount() {
            return parameterNames.size();
        }

        /**
         * 引数の宣言型を、ソースコードに書かれたそのままの文字列で返す
         * (例: {@code "User"}, {@code "long"}, {@code "java.util.List<User>"})。
         *
         * <p>このアナライザーは Java のシンボル解決(import 解決やクラスパス解析)を
         * 行っていないため、ここで返す文字列は「ソース上の見た目」でしかない。
         * {@code MapperParameterTypeValidator} 側では、この前提を踏まえて
         * 「確信を持てる比較だけ行い、怪しい場合はフェイルオープンする」設計にしている。</p>
         */
        public List<String> getParameterTypes() {
            return parameterTypes;
        }

        /**
         * 唯一の引数が可変長引数({@code User... users} のような vararg)かどうか。
         * vararg は実質「配列」なので、宣言型の文字列だけを見て単一オブジェクトとして
         * parameterType と比較すると誤検知になりかねない。呼び出し側はこのフラグが
         * true のとき、parameterType 比較そのものをスキップする。
         */
        public boolean isSingleParameterVarArgs() {
            return singleParameterIsVarArgs;
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
