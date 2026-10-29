package com.mybatislint.analyzer.javaindex;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.mybatislint.analyzer.discovery.ExcludePatterns;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    // 大文字/小文字の揺れを吸収するため、小文字化して保持する。
    // MyBatis の TypeAliasRegistry も内部的には alias 文字列を小文字化して
    // 管理しているため、この索引の比較方法を合わせておくと自然な挙動になる。
    private final Set<String> simpleNamesLowerCase = new HashSet<>();
    // 設定ファイルで登録された alias。Java ソースが 1 つもないかどうか(isEmpty)の
    // 判断には使わないので、simple name とは分けて持つ。
    private final Set<String> registeredAliasesLowerCase = new HashSet<>();
    // resultType などの値からクラスを決めるための対応表(キーは小文字)。
    // simple name は同じ名前のクラスが複数あるとどれか決められないので、候補をすべて持つ。
    private final Map<String, Set<String>> qualifiedNamesBySimpleNameLowerCase = new HashMap<>();
    // @Alias で付けた名前と、それが指すクラス。同じ alias が複数あれば値を null にする。
    private final Map<String, String> aliasAnnotationTargetsLowerCase = new HashMap<>();
    // クラス自身が宣言しているプロパティ(親クラスの分は含まない)。判断できないクラスは null。
    private final Map<String, Set<String>> resultPropertiesByQualifiedName = new HashMap<>();
    // クラス自身が宣言しているフィールドの型(書かれたままの simple name。型引数は外す)。
    private final Map<String, Map<String, String>> fieldTypeNamesByQualifiedName = new HashMap<>();
    // クラス自身が宣言している、getter かフィールドで値を読めるプロパティ。判断できなければ null。
    private final Map<String, Set<String>> readablePropertiesByQualifiedName = new HashMap<>();
    // ワークスペースのどこかで extends / implements されている型の simple name。
    // 同じ simple name の型が複数あっても漏らさないよう、directSupertypesBySimpleName とは別に持つ。
    private final Set<String> extendedSimpleNames = new HashSet<>();
    // 親クラスの FQCN。親がなければキー自体がなく、親を解決できなければ値が null。
    private final Map<String, String> superclassByQualifiedName = new HashMap<>();
    // resultMap の type に書けるクラス(抽象クラスは MyBatis がインスタンスを作れない)。
    private final Set<String> instantiableQualifiedNames = new HashSet<>();
    private final Map<String, Map<String, String>> resultPropertyTypesByQualifiedName = new HashMap<>();
    // resultType と戻り値型の代入互換性を見るための、simple name ごとの直接の親型(simple name)。
    // 同じ simple name の型が複数あるとどれを指すか決められないので値を null にする。
    private final Map<String, Set<String>> directSupertypesBySimpleName = new HashMap<>();
    // 引数なしのコンストラクタがないクラスで、MyBatis が自動マッピングに使うコンストラクタ。
    private final Map<String, AutoMappingConstructor> autoMappingConstructorsByQualifiedName = new HashMap<>();
    private boolean lombokAddsNoArgsConstructor;

    public static JavaTypeIndex empty() {
        return new JavaTypeIndex();
    }

    public static JavaTypeIndex build(Path workspaceRoot) {
        return build(workspaceRoot, ExcludePatterns.none());
    }

    public static JavaTypeIndex build(Path workspaceRoot, ExcludePatterns excludes) {
        return build(JavaSources.parse(workspaceRoot, excludes));
    }

    public static JavaTypeIndex build(JavaSources sources) {
        JavaTypeIndex index = new JavaTypeIndex();
        index.lombokAddsNoArgsConstructor = sources.lombokAddsNoArgsConstructor();
        for (JavaSources.ParsedFile parsed : sources.files()) {
            index.indexJavaFile(parsed.unit());
        }
        return index;
    }

    /** 与えられた simple name に対応するクラス/インターフェースが見つかったかどうか。 */
    public boolean containsSimpleName(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return false;
        }
        String lowerCase = simpleName.toLowerCase(Locale.ROOT);
        return simpleNamesLowerCase.contains(lowerCase) || registeredAliasesLowerCase.contains(lowerCase);
    }

    public boolean isEmpty() {
        return simpleNamesLowerCase.isEmpty();
    }

    /**
     * mybatis-config.xml の {@code <typeAlias alias="...">} など、Java ソース以外で
     * 登録された alias を追加する。simple name と同じく小文字で比較する。
     */
    public void addRegisteredAliases(Set<String> aliases) {
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                registeredAliasesLowerCase.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** この simple name(大文字小文字を区別)の型がワークスペースで宣言されているか。 */
    public boolean declaresType(String simpleName) {
        return directSupertypesBySimpleName.containsKey(simpleName);
    }

    /**
     * 型の直接の親クラス/インターフェースの simple name。宣言されていない、または
     * 同じ simple name の型が複数あって一意に決められない場合は null。
     * enum と record は暗黙の親(Enum / Record)も含める。
     */
    public Set<String> findDirectSupertypes(String simpleName) {
        return directSupertypesBySimpleName.get(simpleName);
    }

    /**
     * resultType などに書かれた値が指すクラスの FQCN。決められなければ null。
     *
     * <p>FQCN はそのまま返す。それ以外は、{@code @Alias} の名前か、ワークスペースで
     * ただ 1 つのクラスの simple name(大文字小文字は無視)のときだけ決める。
     * MyBatis の設定ファイルで登録された alias は指す先を追っていないので決めない。</p>
     */
    public String resolveTypeReference(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }
        String lowerCase = trimmed.toLowerCase(Locale.ROOT);
        if (registeredAliasesLowerCase.contains(lowerCase)) {
            return null;
        }
        if (aliasAnnotationTargetsLowerCase.containsKey(lowerCase)) {
            return aliasAnnotationTargetsLowerCase.get(lowerCase);
        }
        Set<String> candidates = qualifiedNamesBySimpleNameLowerCase.get(lowerCase);
        return candidates != null && candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /**
     * FQCN で一意に解決できたクラスに書き込めるプロパティ(親クラスの分を含む)。
     * 判断できない型は null。
     */
    public Set<String> findResultProperties(String qualifiedName) {
        if (qualifiedName == null || !instantiableQualifiedNames.contains(qualifiedName)) {
            return null;
        }
        return collectInheritedProperties(resultPropertiesByQualifiedName, qualifiedName, new HashSet<>());
    }

    /**
     * 引数なしのコンストラクタがなく、MyBatis が SELECT 句の列を順に渡すコンストラクタ。
     * 引数なしのコンストラクタがある、またはソースから決められないクラスは null。
     */
    public AutoMappingConstructor findAutoMappingConstructor(String qualifiedName) {
        return qualifiedName == null || !instantiableQualifiedNames.contains(qualifiedName)
                ? null : autoMappingConstructorsByQualifiedName.get(qualifiedName);
    }

    /**
     * Mapper メソッドの引数として渡されたオブジェクトから、{@code #{...}} で読める
     * プロパティ(getter とフィールド、親クラスの分を含む)。判断できない型は null。
     *
     * <p>実行時には宣言した型の子クラスのオブジェクトが渡されることがあり、その場合は
     * 子クラスのプロパティも読める。そのため、ワークスペースに子クラスがあるクラスと
     * 抽象クラスは判断しない。</p>
     */
    public Set<String> findReadableProperties(String qualifiedName) {
        if (qualifiedName == null || !instantiableQualifiedNames.contains(qualifiedName)) {
            return null;
        }
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        if (extendedSimpleNames.contains(simpleName)) {
            return null;
        }
        return collectInheritedProperties(readablePropertiesByQualifiedName, qualifiedName, new HashSet<>());
    }

    /**
     * フィールドの型の simple name(int、Integer、String など)。親クラスのフィールドも探す。
     * 見つからない、または親クラスをたどれなければ null。
     */
    public String findFieldTypeName(String qualifiedName, String fieldName) {
        Set<String> visited = new HashSet<>();
        String current = qualifiedName;
        while (current != null && visited.add(current)) {
            Map<String, String> fields = fieldTypeNamesByQualifiedName.get(current);
            if (fields == null) {
                return null;
            }
            if (fields.containsKey(fieldName)) {
                return fields.get(fieldName);
            }
            current = superclassByQualifiedName.get(current);
        }
        return null;
    }

    /**
     * Java のソースに書かれた型名(simple name か FQCN)の FQCN。simple name は、
     * ワークスペースにその名前のクラスがただ 1 つあるときだけ決める。
     */
    public String resolveJavaTypeName(String typeName) {
        if (typeName == null || typeName.isBlank() || typeName.contains("<") || typeName.contains("[")) {
            return null;
        }
        String trimmed = typeName.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }
        Set<String> candidates = qualifiedNamesBySimpleNameLowerCase.get(trimmed.toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.size() != 1) {
            return null;
        }
        String candidate = candidates.iterator().next();
        return candidate.endsWith("." + trimmed) || candidate.equals(trimmed) ? candidate : null;
    }

    private Set<String> collectInheritedProperties(Map<String, Set<String>> ownProperties, String qualifiedName,
                                                   Set<String> visited) {
        // 継承が循環している(コンパイルできないコード)場合も、止まって判断を諦める。
        if (!visited.add(qualifiedName)) {
            return null;
        }
        Set<String> own = ownProperties.get(qualifiedName);
        if (own == null) {
            return null;
        }
        if (!superclassByQualifiedName.containsKey(qualifiedName)) {
            return own;
        }
        // 親クラスがワークスペースにない(フレームワークの基底クラスなど)と、
        // 親が持つプロパティがわからないので判断しない。
        String superclass = superclassByQualifiedName.get(qualifiedName);
        Set<String> inherited = superclass == null ? null
                : collectInheritedProperties(ownProperties, superclass, visited);
        if (inherited == null) {
            return null;
        }
        Set<String> all = new HashSet<>(own);
        all.addAll(inherited);
        return Set.copyOf(all);
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

    private void indexJavaFile(CompilationUnit unit) {
        // クラス/インターフェースの両方を対象にする。DTO/Entity は class、
        // まれに interface で宣言されることもあるため、どちらも候補として拾う
        // (record と enum は下で別に拾う)。
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            simpleNamesLowerCase.add(type.getNameAsString().toLowerCase(Locale.ROOT));
            String alias = addAliasAnnotation(type);
            if (type.getParentNode().orElse(null) instanceof CompilationUnit) {
                type.getFullyQualifiedName().ifPresent(name -> {
                    qualifiedNamesBySimpleNameLowerCase
                            .computeIfAbsent(type.getNameAsString().toLowerCase(Locale.ROOT),
                                    ignored -> new HashSet<>())
                            .add(name);
                    if (alias != null) {
                        String key = alias.toLowerCase(Locale.ROOT);
                        aliasAnnotationTargetsLowerCase.put(key,
                                aliasAnnotationTargetsLowerCase.containsKey(key) ? null : name);
                    }
                });
            }
            Set<String> supertypes = new HashSet<>(simpleNamesOf(type.getExtendedTypes()));
            supertypes.addAll(simpleNamesOf(type.getImplementedTypes()));
            addDirectSupertypes(type.getNameAsString(), supertypes);
            if (type.getParentNode().orElse(null) instanceof CompilationUnit) {
                type.getFullyQualifiedName().ifPresent(name -> {
                    // 同じ FQCN が複数のソースルートにある場合も決め打ちしない。
                    if (resultPropertiesByQualifiedName.containsKey(name)) {
                        resultPropertiesByQualifiedName.put(name, null);
                        readablePropertiesByQualifiedName.put(name, null);
                        autoMappingConstructorsByQualifiedName.remove(name);
                    } else {
                        AutoMappingConstructor constructor = AutoMappingConstructor.of(type, lombokAddsNoArgsConstructor);
                        if (constructor != null) {
                            autoMappingConstructorsByQualifiedName.put(name, constructor);
                        }
                        resultPropertiesByQualifiedName.put(name, collectResultProperties(type));
                        readablePropertiesByQualifiedName.put(name, collectReadableProperties(type));
                        fieldTypeNamesByQualifiedName.put(name, collectFieldTypeNames(type));
                        if (!type.isInterface() && !type.isAbstract()) {
                            instantiableQualifiedNames.add(name);
                        }
                        if (!type.isInterface() && !type.getExtendedTypes().isEmpty()) {
                            superclassByQualifiedName.put(name,
                                    resolveSuperclass(type.getExtendedTypes().get(0), unit));
                        }
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
            addAliasAnnotation(recordDeclaration);
            Set<String> supertypes = new HashSet<>(simpleNamesOf(recordDeclaration.getImplementedTypes()));
            supertypes.add("Record");
            addDirectSupertypes(recordDeclaration.getNameAsString(), supertypes);
            if (recordDeclaration.getParentNode().orElse(null) instanceof CompilationUnit) {
                recordDeclaration.getFullyQualifiedName()
                        .ifPresent(name -> resultPropertiesByQualifiedName.put(name, null));
            }
        }
        // enum も resultType / parameterType に書ける(パッケージ単位の alias 登録の
        // 対象にもなる)。見落とすと enum を指定した箇所が誤検知になる。
        for (EnumDeclaration enumDeclaration : unit.findAll(EnumDeclaration.class)) {
            simpleNamesLowerCase.add(enumDeclaration.getNameAsString().toLowerCase(Locale.ROOT));
            addAliasAnnotation(enumDeclaration);
            Set<String> supertypes = new HashSet<>(simpleNamesOf(enumDeclaration.getImplementedTypes()));
            supertypes.add("Enum");
            addDirectSupertypes(enumDeclaration.getNameAsString(), supertypes);
        }
    }

    /**
     * {@code @Alias("Member")} が付いた型は、simple name ではなくその名前で登録される。
     * 名前を読み取れない書き方(定数の参照など)は無視する。
     */
    private String addAliasAnnotation(NodeWithAnnotations<?> type) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            String name = annotation.getNameAsString();
            if (!"Alias".equals(name) && !name.endsWith(".Alias")) {
                continue;
            }
            String alias = null;
            if (annotation instanceof SingleMemberAnnotationExpr single
                    && single.getMemberValue() instanceof StringLiteralExpr literal) {
                alias = literal.getValue();
            } else if (annotation instanceof NormalAnnotationExpr normal) {
                alias = normal.getPairs().stream()
                        .filter(pair -> "value".equals(pair.getNameAsString()))
                        .filter(pair -> pair.getValue() instanceof StringLiteralExpr)
                        .map(pair -> ((StringLiteralExpr) pair.getValue()).getValue())
                        .findFirst().orElse(null);
            }
            if (alias != null) {
                simpleNamesLowerCase.add(alias.toLowerCase(Locale.ROOT));
                return alias;
            }
        }
        return null;
    }

    private void addDirectSupertypes(String simpleName, Set<String> supertypes) {
        extendedSimpleNames.addAll(supertypes);
        if (directSupertypesBySimpleName.containsKey(simpleName)) {
            directSupertypesBySimpleName.put(simpleName, null);
        } else {
            directSupertypesBySimpleName.put(simpleName, Set.copyOf(supertypes));
        }
    }

    private static List<String> simpleNamesOf(List<ClassOrInterfaceType> types) {
        // getNameAsString() はパッケージ部分と型引数を除いた名前を返す。
        return types.stream().map(ClassOrInterfaceType::getNameAsString).toList();
    }

    /**
     * クラス自身が宣言している、MyBatis が値を書き込めるプロパティ。
     *
     * <p>MyBatis は setter があれば setter を、なければフィールドに直接書き込む
     * (private でもよい)。そのため「フィールド名」と「setter から求めた名前」の
     * 両方がプロパティになる。getter は結果の書き込みには使われないので数えない。
     * Lombok の {@code @Data} / {@code @Setter} などが作る setter はフィールド名から
     * 決まるので、フィールドを数えれば足りる。</p>
     *
     * <p>実装しているインターフェースは数えない。インターフェースはフィールドを持てず、
     * setter を default メソッドで持たせることもまずないため。</p>
     */
    private static Set<String> collectResultProperties(ClassOrInterfaceDeclaration type) {
        // 型引数(class BaseEntity<ID>)はプロパティの名前には関係しないので、
        // ジェネリクスのクラスも数える。
        if (type.isInterface()) {
            return null;
        }
        for (AnnotationExpr annotation : type.findAll(AnnotationExpr.class)) {
            String name = annotation.getName().getIdentifier();
            // @Accessors(prefix = "m") はフィールド名と違う名前の setter を作り、
            // @Delegate は別の型の setter をまるごと追加する。どちらも
            // フィールドからは名前を決められない。
            if ("Accessors".equals(name) || "Delegate".equals(name)) {
                return null;
            }
        }
        Set<String> properties = new HashSet<>();
        for (var field : type.getFields()) {
            field.getVariables().forEach(variable -> {
                String name = variable.getNameAsString();
                properties.add(name);
                // Lombok は boolean の isActive フィールドに setActive を作る。
                if (variable.getType().asString().equals("boolean")
                        && name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                    properties.add(Character.toLowerCase(name.charAt(2)) + name.substring(3));
                }
            });
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

    /**
     * クラス自身が宣言している、MyBatis が値を読めるプロパティ。
     *
     * <p>MyBatis は getter があれば getter を、なければフィールドから直接読む。
     * そのため「フィールド名」と「getter から求めた名前」の両方がプロパティになる。
     * Lombok の getter はフィールド名から決まるので、フィールドを数えれば足りる。</p>
     */
    private static Set<String> collectReadableProperties(ClassOrInterfaceDeclaration type) {
        if (type.isInterface() || usesAccessorChangingAnnotation(type)) {
            return null;
        }
        Set<String> properties = new HashSet<>();
        for (var field : type.getFields()) {
            field.getVariables().forEach(variable -> {
                String name = variable.getNameAsString();
                properties.add(name);
                // Lombok は boolean の isActive フィールドに isActive() を作り、名前は active になる。
                if (variable.getType().asString().equals("boolean")
                        && name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                    properties.add(decapitalize(name.substring(2)));
                }
            });
        }
        for (var method : type.getMethods()) {
            String name = method.getNameAsString();
            if (!method.getParameters().isEmpty() || method.getType().isVoidType()) {
                continue;
            }
            if (name.startsWith("get") && name.length() > 3) {
                properties.add(decapitalize(name.substring(3)));
            } else if (name.startsWith("is") && name.length() > 2) {
                properties.add(decapitalize(name.substring(2)));
            }
        }
        return Set.copyOf(properties);
    }

    private static Map<String, String> collectFieldTypeNames(ClassOrInterfaceDeclaration type) {
        Map<String, String> types = new HashMap<>();
        for (var field : type.getFields()) {
            field.getVariables().forEach(variable -> {
                String name = variable.getType().asString();
                int generic = name.indexOf('<');
                if (generic >= 0) {
                    name = name.substring(0, generic);
                }
                types.put(variable.getNameAsString(), name.substring(name.lastIndexOf('.') + 1));
            });
        }
        return types;
    }

    private static boolean usesAccessorChangingAnnotation(ClassOrInterfaceDeclaration type) {
        // @Accessors(prefix = "m") はフィールド名と違う名前のアクセサを作り、
        // @Delegate は別の型のアクセサをまるごと追加する。どちらも
        // フィールドからは名前を決められない。
        return type.findAll(AnnotationExpr.class).stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> "Accessors".equals(name) || "Delegate".equals(name));
    }

    /** java.beans.Introspector と同じ規則で、getUserName の UserName を userName にする。 */
    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** 親クラスの FQCN。型引数は名前に関係しないので外す。解決できなければ null。 */
    private static String resolveSuperclass(ClassOrInterfaceType superclass, CompilationUnit unit) {
        String name = superclass.getNameWithScope();
        if (name.contains(".")) {
            return name;
        }
        return resolveSimpleName(name, unit);
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
        return resolveSimpleName(name, unit);
    }

    /** import と同じパッケージから simple name の FQCN を決める。決められなければ null。 */
    private static String resolveSimpleName(String name, CompilationUnit unit) {
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
