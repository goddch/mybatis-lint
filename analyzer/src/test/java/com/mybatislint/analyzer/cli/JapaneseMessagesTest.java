package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Language;
import com.mybatislint.analyzer.testutil.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JapaneseMessagesTest {

    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");

    @Test
    void everyFixtureDiagnosticHasAJapaneseMessage() {
        Path fixtures = TestFixtures.fixturesRoot();
        List<Diagnostic> diagnostics = new ArrayList<>(new AnalyzerCli().run(fixtures).getDiagnostics());
        // fixtures 全体では DB が 1 つに決まらず SQL の構文チェックが動かないので、Oracle の例は別に解析する。
        diagnostics.addAll(new AnalyzerCli().run(fixtures.resolve("sql-syntax-validation/oracle")).getDiagnostics());

        assertTrue(diagnostics.size() > 50, "the fixtures should produce many diagnostics");
        for (Diagnostic diagnostic : diagnostics) {
            // 訳し忘れて英語の文を 2 回書いたものを見つける。
            String japanese = diagnostic.getMessage(Language.JA);
            assertTrue(JAPANESE.matcher(japanese).find(),
                    diagnostic.getRuleId() + " has no Japanese message: " + japanese);
        }
    }
}
