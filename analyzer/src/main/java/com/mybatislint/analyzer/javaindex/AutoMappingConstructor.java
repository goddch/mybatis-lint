package com.mybatislint.analyzer.javaindex;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引数なしのコンストラクタがないクラスで、MyBatis が resultType の自動マッピングに使う
 * ただ 1 つのコンストラクタ。
 *
 * <p>MyBatis は引数なしのコンストラクタがないと、SELECT 句の列を<b>先頭から順に</b>
 * コンストラクタの引数へ渡す(列の名前は見ない)。そのあと、名前が一致する列を
 * プロパティにも書き込む(setter がなければ final のフィールドにも直接書き込む)。
 * そのため、名前が一致する列のないフィールドには、同じ位置にあった別の列の値が残る。</p>
 *
 * @param parameterCount コンストラクタの引数の数
 * @param fieldNames     引数を受け取るフィールドの名前(引数の順)。Lombok が作るコンストラクタ
 *                       だけ決まり、ソースに書かれたコンストラクタは本体が何をするかわからないので null
 */
public record AutoMappingConstructor(int parameterCount, List<String> fieldNames) {

    private static final Set<String> CONSTRUCTOR_ANNOTATIONS =
            Set.of("NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor");
    // コンストラクタの作られ方が変わり、ソースから決められない Lombok の注釈。
    // @Builder は条件によって全フィールドのコンストラクタを足す。@FieldDefaults と @NonFinal は
    // final になるフィールドを変える。@Tolerate は書いたコンストラクタを Lombok に無視させる。
    private static final Set<String> UNPREDICTABLE_ANNOTATIONS =
            Set.of("Builder", "SuperBuilder", "FieldDefaults", "NonFinal", "Tolerate");

    /**
     * クラスの自動マッピング用のコンストラクタ。引数なしのコンストラクタがある、コンストラクタが
     * 2 つ以上ある(MyBatis は引数の型で選ぶ)、ソースから決められない場合は null。
     *
     * @param lombokAddsNoArgsConstructor lombok.config の {@code lombok.noArgsConstructor.extraPrivate}
     *                                    が有効で、{@code @Data} / {@code @Value} が private の
     *                                    引数なしコンストラクタも作るかもしれない
     */
    static AutoMappingConstructor of(ClassOrInterfaceDeclaration type, boolean lombokAddsNoArgsConstructor) {
        if (type.isInterface() || type.isAbstract()) {
            return null;
        }
        Set<String> annotations = new HashSet<>();
        for (AnnotationExpr annotation : type.findAll(AnnotationExpr.class)) {
            annotations.add(annotation.getName().getIdentifier());
        }
        if (annotations.contains("NoArgsConstructor") || annotations.stream().anyMatch(UNPREDICTABLE_ANNOTATIONS::contains)) {
            return null;
        }
        List<ConstructorDeclaration> declared = type.getConstructors();
        List<AutoMappingConstructor> constructors = new ArrayList<>();
        for (ConstructorDeclaration constructor : declared) {
            if (constructor.getParameters().stream().anyMatch(parameter -> parameter.isVarArgs())) {
                return null;
            }
            constructors.add(new AutoMappingConstructor(constructor.getParameters().size(), null));
        }
        boolean data = hasClassAnnotation(type, "Data");
        boolean value = hasClassAnnotation(type, "Value");
        if (hasClassAnnotation(type, "AllArgsConstructor")) {
            constructors.add(fromFields(type, false, value));
        }
        if (hasClassAnnotation(type, "RequiredArgsConstructor")) {
            constructors.add(fromFields(type, true, value));
        }
        // @Data と @Value は、コンストラクタを書いておらず、Lombok のコンストラクタの注釈も
        // ないときだけコンストラクタを作る。
        boolean lombokConstructorAnnotation = CONSTRUCTOR_ANNOTATIONS.stream()
                .anyMatch(name -> hasClassAnnotation(type, name));
        if ((data || value) && declared.isEmpty() && !lombokConstructorAnnotation) {
            if (lombokAddsNoArgsConstructor) {
                return null;
            }
            constructors.add(fromFields(type, !value, value));
        }
        // コンストラクタが 1 つもなければ、コンパイラが引数なしのコンストラクタを作る。
        if (constructors.size() != 1 || constructors.get(0).parameterCount() == 0) {
            return null;
        }
        return constructors.get(0);
    }

    /**
     * Lombok が作るコンストラクタ。引数はフィールドの宣言順に並ぶ。static フィールドと、
     * 初期値のある final フィールドは引数にならない。
     *
     * @param requiredOnly {@code @RequiredArgsConstructor} / {@code @Data} のように、
     *                     final か {@code @NonNull} のフィールドだけを引数にする
     * @param allFinal     {@code @Value} はすべてのフィールドを final にする
     */
    private static AutoMappingConstructor fromFields(ClassOrInterfaceDeclaration type, boolean requiredOnly,
                                                     boolean allFinal) {
        List<String> names = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            boolean isFinal = allFinal || field.isFinal();
            boolean nonNull = field.getAnnotations().stream()
                    .anyMatch(annotation -> "NonNull".equals(annotation.getName().getIdentifier()));
            field.getVariables().forEach(variable -> {
                boolean initialized = variable.getInitializer().isPresent();
                if (isFinal && initialized) {
                    return;
                }
                if (!requiredOnly || ((isFinal || nonNull) && !initialized)) {
                    names.add(variable.getNameAsString());
                }
            });
        }
        return new AutoMappingConstructor(names.size(), List.copyOf(names));
    }

    // staticName を付けても、コンストラクタが private になるだけで数は変わらない
    // (MyBatis は private のコンストラクタも使う)。
    private static boolean hasClassAnnotation(ClassOrInterfaceDeclaration type, String name) {
        return type.getAnnotations().stream().anyMatch(annotation -> name.equals(annotation.getName().getIdentifier()));
    }
}
