package com.mybatislint.analyzer.javaindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.Type;
import com.mybatislint.analyzer.discovery.JavaFileScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ワークスペース内にある Java 型の simple name と、通常クラスの
 * 明示的な resultMap プロパティ検証に使うフィールド/setter を集めた軽量な索引。
 *
 * <p>MyBatis の {@code typeAlias} は、設定ファイル(mybatis-config.xml)側の
 * {@code <typeAliases>} や {@code typeAliasesPackage}、あるいは
 * {@code TypeAliasRegistry} への明示的な登録によって、実行時に初めて
 * 「どの文字列がどのクラスを指すか」が確定する。mapper XML と Java ソースだけを
 * 静的に読んでいるこのアナライザーからは、それらの設定ファイルを必ずしも
 * 見つけられるとは限らない。つまり <b>typeAlias を 100% 正しく解決することは、
 * このアナライザーの入力だけでは原理的にできない。</b></p>
 *
 * <p>そこで本索引は、「もし専用の alias 登録がなければ、MyBatis はデフォルトで
 * クラスの simple name(パッケージなしの名前)を alias として使う」という
 * デフォルト挙動を手がかりに、ワークスペース内で見つかる simple name の集合だけを
 * 保持する。呼び出し側(型検証ロジック)は、この集合に無い名前を
 * 「未解決の可能性が高い候補」として扱うが、あくまで heuristic であり、
 * 断定はしない設計にしている。</p>
 */
public final class JavaTypeIndex {

    // JavaParser のデフォルト言語レベルは record 宣言(Java 14+)を解析できない。
    // このプロジェクトは Java 17 を前提にしているため(README/roadmap 参照)、
    // record ベースの DTO を見落とさないよう明示的に JAVA_17 を指定する。
    private static final ParserConfiguration PARSER_CONFIGURATION =
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

    // 大文字/小文字の揺れを吸収するため、小文字化して保持する。
    // MyBatis の TypeAliasRegistry も内部的には alias 文字列を小文字化して
    // 管理しているため、この索引の比較方法を合わせておくと自然な挙動になる。
    private final Set<String> simpleNamesLowerCase = new HashSet<>();
    private final Map<String, Set<String>> resultPropertiesByQualifiedName = new HashMap<>();
    private final Map<String, Map<String, String>> resultPropertyTypesByQualifiedName = new HashMap<>();

    public static JavaTypeIndex empty() {
        return new JavaTypeIndex();
    }

    public static JavaTypeIndex build(Path workspaceRoot) {
        JavaTypeIndex index = new JavaTypeIndex();
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return index;
        }
        for (Path file : new JavaFileScanner().scan(workspaceRoot)) {
            index.indexJavaFile(file);
        }
        return index;
    }

    /** 与えられた simple name に対応するクラス/インターフェースが見つかったかどうか。 */
    public boolean containsSimpleName(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return false;
        }
        return simpleNamesLowerCase.contains(simpleName.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return simpleNamesLowerCase.isEmpty();
    }

    /** FQCN で一意に解決できた通常クラスのプロパティ。判断できない型は null。 */
    public Set<String> findResultProperties(String qualifiedName) {
        return resultPropertiesByQualifiedName.get(qualifiedName);
    }

    /** 通常クラスの単純なプロパティ型。一意に解決できない場合は null。 */
    public String findResultPropertyType(String qualifiedName, String property) {
        if (findResultProperties(qualifiedName) == null) {
            return null;
        }
        Map<String, String> types = resultPropertyTypesByQualifiedName.get(qualifiedName);
        String type = types == null ? null : types.get(property);
        return findResultProperties(type) == null ? null : type;
    }

    private void indexJavaFile(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> parseResult = new JavaParser(PARSER_CONFIGURATION).parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return;
            }
            CompilationUnit unit = parseResult.getResult().get();
            // クラス/インターフェースの両方を対象にする。DTO/Entity は class、
            // まれに interface で宣言されることもあるため、どちらも候補として拾う
            // (enum は今回のスコープでは対象外。まずは小さく始める方針のため)。
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                simpleNamesLowerCase.add(type.getNameAsString().toLowerCase(Locale.ROOT));
                if (type.getParentNode().orElse(null) instanceof CompilationUnit) {
                    type.getFullyQualifiedName().ifPresent(name -> {
                        // 同じ FQCN が複数のソースルートにある場合も決め打ちしない。
                        if (resultPropertiesByQualifiedName.containsKey(name)) {
                            resultPropertiesByQualifiedName.put(name, null);
                        } else {
                            resultPropertiesByQualifiedName.put(name, collectResultProperties(type));
                            if (findResultProperties(name) != null) {
                                resultPropertyTypesByQualifiedName.put(name, collectResultPropertyTypes(type, unit));
                            }
                        }
                    });
                }
            }
            // record も DTO/Entity の定義によく使われる(Java 16+)。
            // ClassOrInterfaceDeclaration には含まれない別ノード種別なので、
            // 見落とすと record ベースの DTO で誤検知(false positive)になるため
            // 明示的に拾っておく。
            for (RecordDeclaration recordDeclaration : unit.findAll(RecordDeclaration.class)) {
                simpleNamesLowerCase.add(recordDeclaration.getNameAsString().toLowerCase(Locale.ROOT));
                if (recordDeclaration.getParentNode().orElse(null) instanceof CompilationUnit) {
                    recordDeclaration.getFullyQualifiedName()
                            .ifPresent(name -> resultPropertiesByQualifiedName.put(name, null));
                }
            }
        } catch (IOException e) {
            // 1 ファイル読めないだけでワークスペース全体の索引作りを止めたくないため、
            // このファイルの分は諦めて次のファイルに進むフェイルオープン方針。
        }
    }

    private static Set<String> collectResultProperties(ClassOrInterfaceDeclaration type) {
        if (type.isInterface() || type.isAbstract()
                || !type.getExtendedTypes().isEmpty() || !type.getImplementedTypes().isEmpty()
                || !type.getTypeParameters().isEmpty() || !type.findAll(AnnotationExpr.class).isEmpty()) {
            // 継承やコード生成で追加されるプロパティはソース単体では断定できない。
            return null;
        }
        Set<String> properties = new HashSet<>();
        for (var field : type.getFields()) {
            // MyBatis は private フィールドにも直接アクセスできる。
            field.getVariables().forEach(variable -> properties.add(variable.getNameAsString()));
        }
        for (var method : type.getMethods()) {
            String name = method.getNameAsString();
            if (name.startsWith("set") && name.length() > 3 && method.getParameters().size() == 1) {
                String property = name.substring(3);
                if (property.length() == 1 || !Character.isUpperCase(property.charAt(1))) {
                    property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
                }
                properties.add(property);
            }
        }
        return Set.copyOf(properties);
    }

    private static Map<String, String> collectResultPropertyTypes(ClassOrInterfaceDeclaration type,
                                                                 CompilationUnit unit) {
        Map<String, String> types = new HashMap<>();
        // メンバー型の名前解決はクラスパスなしでは決め打ちしない。
        if (type.getMembers().stream().anyMatch(member -> member instanceof TypeDeclaration<?>)) {
            return types;
        }
        for (var field : type.getFields()) {
            field.getVariables().forEach(variable -> addPropertyType(types, variable.getNameAsString(),
                    field.isStatic() ? null : resolvePropertyType(variable.getType(), unit)));
        }
        for (var method : type.getMethods()) {
            String name = method.getNameAsString();
            if (name.startsWith("set") && name.length() > 3 && method.getParameters().size() == 1) {
                String property = name.substring(3);
                if (property.length() == 1 || !Character.isUpperCase(property.charAt(1))) {
                    property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
                }
                var parameter = method.getParameter(0);
                addPropertyType(types, property,
                        method.isStatic() || !method.getTypeParameters().isEmpty() || parameter.isVarArgs()
                                ? null : resolvePropertyType(parameter.getType(), unit));
            }
        }
        return types;
    }

    private static void addPropertyType(Map<String, String> types, String property, String type) {
        // フィールドと setter、または overload 間で型が異なる場合は fail-open。
        if (types.containsKey(property) && !Objects.equals(types.get(property), type)) {
            types.put(property, null);
        } else {
            types.put(property, type);
        }
    }

    private static String resolvePropertyType(Type type, CompilationUnit unit) {
        if (!type.isClassOrInterfaceType()) {
            return null;
        }
        String name = type.asString();
        if (name.contains("<") || name.contains("@")) {
            return null;
        }
        if (name.contains(".")) {
            return name;
        }
        String imported = null;
        for (var declaration : unit.getImports()) {
            if (declaration.isAsterisk() || declaration.isStatic()) {
                return null;
            }
            String candidate = declaration.getNameAsString();
            if (candidate.endsWith("." + name)) {
                if (imported != null && !imported.equals(candidate)) {
                    return null;
                }
                imported = candidate;
            }
        }
        if (imported != null) {
            return imported;
        }
        return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + "." + name)
                .orElse(null);
    }
}
