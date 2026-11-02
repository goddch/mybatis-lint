package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.cli.AnalyzerCli;
import com.mybatislint.analyzer.discovery.ExcludePatterns;
import com.mybatislint.analyzer.model.RuleIds;

import java.util.List;

/** テスト用の AnalyzerCli。 */
final class TestAnalyzers {

    private TestAnalyzers() {
    }

    /**
     * 使われていない定義のルールを止めた AnalyzerCli。resultMap や sql の検証のテストは、
     * 参照されない定義を含む短い XML で「ほかの診断が出ない」ことを確かめるため。
     */
    static AnalyzerCli ignoringUnusedDefinitions() {
        return new AnalyzerCli(ExcludePatterns.none(), List.of(RuleIds.UNUSED_SQL_FRAGMENT, RuleIds.UNUSED_RESULT_MAP,
                RuleIds.STATEMENT_WITHOUT_METHOD));
    }
}
