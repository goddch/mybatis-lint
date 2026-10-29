package com.mybatislint.analyzer.cli;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.RuleIds;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 利用者が止めた診断を取り除く。
 *
 * <p>誤検知が出たときに、修正版のリリースを待たずに利用者の側で黙らせられるようにするためのもの。
 * 止め方は 2 つある。</p>
 * <ul>
 *   <li>{@code --disable <rule>}: そのルールの診断をすべて出さない</li>
 *   <li>ファイル内のコメント: {@code mybatis-lint-disable-next-line [rule ...]} は次の行だけ、
 *       {@code mybatis-lint-disable-file [rule ...]} はそのファイル全体で診断を出さない。
 *       ルールを書かなければすべてのルールが対象になる。XML でも Java でも、
 *       コメントの書き方は問わない(指示とルールは 1 行に書く)</li>
 * </ul>
 * <p>ルールは {@code mybatis-lint/} を付けても付けなくてもよい。</p>
 */
public final class DiagnosticFilter {

    static final String NEXT_LINE_DIRECTIVE = "mybatis-lint-disable-next-line";
    static final String FILE_DIRECTIVE = "mybatis-lint-disable-file";
    private static final String RULE_PREFIX = "mybatis-lint/";
    /** 「すべてのルール」を表す印。ルール ID とは重ならない。 */
    private static final String ALL_RULES = "*";

    private final Set<String> disabledRules;
    private final Map<String, Suppressions> suppressionsByUri = new HashMap<>();

    public DiagnosticFilter(Collection<String> disabledRules) {
        Set<String> normalized = new HashSet<>();
        for (String rule : disabledRules) {
            String id = normalizeRuleId(rule);
            if (!id.isEmpty()) {
                normalized.add(id);
            }
        }
        this.disabledRules = normalized;
    }

    /** 指定されたルールのうち、存在しないもの(綴りの誤りなど)を返す。 */
    public static List<String> unknownRuleIds(Collection<String> rules) {
        Set<String> known = knownRuleIds();
        List<String> unknown = new ArrayList<>();
        for (String rule : rules) {
            String id = normalizeRuleId(rule);
            if (!id.isEmpty() && !known.contains(id)) {
                unknown.add(rule.trim());
            }
        }
        return unknown;
    }

    public List<Diagnostic> apply(List<Diagnostic> diagnostics) {
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (disabledRules.contains(diagnostic.getRuleId())) {
                continue;
            }
            Suppressions suppressions = suppressionsByUri.computeIfAbsent(diagnostic.getUri(), DiagnosticFilter::load);
            if (suppressions.suppresses(diagnostic.getRange().getStart().getLine(), diagnostic.getRuleId())) {
                continue;
            }
            kept.add(diagnostic);
        }
        return kept;
    }

    private static Suppressions load(String uri) {
        try {
            return Suppressions.parse(Files.readString(Path.of(URI.create(uri)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 読めないファイルにはコメントも書けないので、止めるものはない。
            return Suppressions.NONE;
        }
    }

    static String normalizeRuleId(String rule) {
        String value = rule == null ? "" : rule.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.startsWith(RULE_PREFIX) ? value : RULE_PREFIX + value;
    }

    private static Set<String> knownRuleIds() {
        Set<String> ids = new HashSet<>();
        for (Field field : RuleIds.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && field.getType() == String.class) {
                try {
                    ids.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    // public static なので起こらない。
                }
            }
        }
        return ids;
    }

    /** 1 ファイル分の抑制コメントの内容。 */
    static final class Suppressions {
        static final Suppressions NONE = new Suppressions(Map.of(), Set.of());

        /** 行番号(1 始まり) → その行で止めるルール。 */
        private final Map<Integer, Set<String>> byLine;
        private final Set<String> wholeFile;

        private Suppressions(Map<Integer, Set<String>> byLine, Set<String> wholeFile) {
            this.byLine = byLine;
            this.wholeFile = wholeFile;
        }

        static Suppressions parse(String text) {
            Map<Integer, Set<String>> byLine = new HashMap<>();
            Set<String> wholeFile = new LinkedHashSet<>();
            String[] lines = text.split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // "disable-file" と "disable-next-line" は先頭が共通ではないので、順に探せばよい。
                int fileIndex = line.indexOf(FILE_DIRECTIVE);
                if (fileIndex >= 0) {
                    wholeFile.addAll(rulesAfter(line, fileIndex + FILE_DIRECTIVE.length()));
                }
                int nextLineIndex = line.indexOf(NEXT_LINE_DIRECTIVE);
                if (nextLineIndex >= 0) {
                    byLine.computeIfAbsent(i + 2, key -> new HashSet<>())
                            .addAll(rulesAfter(line, nextLineIndex + NEXT_LINE_DIRECTIVE.length()));
                }
            }
            return new Suppressions(byLine, wholeFile);
        }

        /** 指示の後ろからコメントの終わりまでに書かれたルール。何もなければ「すべて」。 */
        private static Set<String> rulesAfter(String line, int start) {
            String rest = line.substring(start);
            for (String end : List.of("-->", "*/")) {
                int index = rest.indexOf(end);
                if (index >= 0) {
                    rest = rest.substring(0, index);
                }
            }
            Set<String> rules = new LinkedHashSet<>();
            for (String token : rest.split("[\\s,]+")) {
                String id = normalizeRuleId(token);
                if (!id.isEmpty()) {
                    rules.add(id);
                }
            }
            if (rules.isEmpty()) {
                rules.add(ALL_RULES);
            }
            return rules;
        }

        boolean suppresses(int line, String ruleId) {
            if (matches(wholeFile, ruleId)) {
                return true;
            }
            Set<String> rules = byLine.get(line);
            return rules != null && matches(rules, ruleId);
        }

        private static boolean matches(Set<String> rules, String ruleId) {
            return rules.contains(ALL_RULES) || rules.contains(ruleId);
        }
    }
}
