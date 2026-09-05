package com.mybatislint.analyzer.javaindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.mybatislint.analyzer.discovery.JavaFileScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ワークスペース内にある Java クラス/インターフェース宣言の「simple name」だけを
 * 集めた、軽量な索引。
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

    private void indexJavaFile(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> parseResult = new JavaParser().parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return;
            }
            CompilationUnit unit = parseResult.getResult().get();
            // クラス/インターフェースの両方を対象にする。DTO/Entity は class、
            // まれに interface で宣言されることもあるため、どちらも候補として拾う
            // (enum は今回のスコープでは対象外。まずは小さく始める方針のため)。
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                simpleNamesLowerCase.add(type.getNameAsString().toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            // 1 ファイル読めないだけでワークスペース全体の索引作りを止めたくないため、
            // このファイルの分は諦めて次のファイルに進むフェイルオープン方針。
        }
    }
}
