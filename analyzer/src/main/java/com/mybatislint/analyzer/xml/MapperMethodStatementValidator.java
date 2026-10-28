package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.JavaMapperIndex;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.DeclaredMethod;
import com.mybatislint.analyzer.javaindex.JavaMapperIndex.MapperInterfaceInfo;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Position;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Java の Mapper メソッドに、同じ id の statement が mapper XML にないものを見つける
 * ({@code mybatis-lint/missing-mapper-statement})。診断は Java ファイルのメソッド名に付ける。
 *
 * <p>そのメソッドを呼ぶと MyBatis は {@code BindingException: Invalid bound statement (not found)}
 * で失敗する。メソッド名を変えたときに XML の id を直し忘れるとよく起きる。</p>
 *
 * <h2>判断できない場合は何もしない</h2>
 * <ul>
 *   <li>そのインターフェースの FQCN を namespace にした mapper XML がワークスペースにない
 *       (注釈だけで書いた Mapper や、XML が別モジュールにある Mapper)</li>
 *   <li>ほかのインターフェースに extends されている。MyBatis は継承したメソッドの statement を
 *       子の namespace から先に探すので、子の XML で実装されていることがある</li>
 *   <li>同じ FQCN のインターフェースが複数ある、読めない mapper XML がある</li>
 *   <li>default / static メソッド、{@code @Select} などで SQL を書いたメソッド</li>
 * </ul>
 */
public final class MapperMethodStatementValidator {

    private final JavaMapperIndex javaIndex;
    private final MapperStatementIndex statementIndex;

    public MapperMethodStatementValidator(JavaMapperIndex javaIndex, MapperStatementIndex statementIndex) {
        this.javaIndex = javaIndex;
        this.statementIndex = statementIndex;
    }

    /** @param displayPath Java ファイルの表示用パス(ワークスペースからの相対パス)を求める関数 */
    public List<Diagnostic> validate(Function<Path, Path> displayPath) {
        if (javaIndex == null || statementIndex == null || !statementIndex.isComplete()) {
            return Collections.emptyList();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (MapperInterfaceInfo mapper : javaIndex.interfaces()) {
            Set<String> statementIds = statementIndex.findStatementIds(mapper.getQualifiedName());
            if (statementIds == null || mapper.isDuplicated() || javaIndex.isExtendedByAnotherInterface(mapper)) {
                continue;
            }
            for (DeclaredMethod method : mapper.getDeclaredMethods()) {
                if (!method.needsStatement() || statementIds.contains(method.name())) {
                    continue;
                }
                Range range = new Range(new Position(method.line(), method.column()),
                        new Position(method.line(), method.endColumn()));
                diagnostics.add(new Diagnostic(displayPath.apply(mapper.getFile()), mapper.getFile().toUri().toString(),
                        range, Severity.WARNING, RuleIds.MISSING_MAPPER_STATEMENT,
                        "Mapper method '" + method.name() + "' has no <select>/<insert>/<update>/<delete> with id '"
                                + method.name() + "' in namespace '" + mapper.getQualifiedName()
                                + "'; calling it fails with BindingException."));
            }
        }
        // 同じ実行で結果が変わらないよう、ファイルと位置の順に並べる。
        diagnostics.sort((a, b) -> {
            int byFile = a.getFile().toString().compareTo(b.getFile().toString());
            if (byFile != 0) {
                return byFile;
            }
            return Integer.compare(a.getRange().getStart().getLine(), b.getRange().getStart().getLine());
        });
        return diagnostics;
    }
}
