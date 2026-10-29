package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import com.mybatislint.analyzer.xml.MapperStatementIndex.Occurrence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同じ namespace の別々の mapper XML に、同じ id の statement があるものを見つける
 * ({@code mybatis-lint/duplicate-statement-id})。MyBatis はアプリの起動時に
 * {@code Mapped Statements collection already contains value} で失敗する。
 *
 * <p>1 つのファイルの中の重複は {@link MapperXmlAnalyzer} が報告するので、ここでは
 * 2 つ以上のファイルにまたがるものだけを、各ファイルの最初の宣言に報告する。
 * databaseId 属性を使っている id は、データベースごとに書き分けている可能性があるので見ない。</p>
 */
public final class MapperDuplicateStatementValidator {

    private static final Pattern STATEMENT_TAG = Pattern.compile("<(select|insert|update|delete)(?=[\\s>/])");

    private final MapperStatementIndex statementIndex;

    public MapperDuplicateStatementValidator(MapperStatementIndex statementIndex) {
        this.statementIndex = statementIndex;
    }

    /** @param displayPath ファイルの表示用パス(ワークスペースからの相対パス)を求める関数 */
    public List<Diagnostic> validate(Function<Path, Path> displayPath) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<Path, String> texts = new HashMap<>();
        statementIndex.occurrences().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(namespace -> namespace.getValue().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(id -> report(namespace.getKey(), id.getKey(), id.getValue(), texts, displayPath,
                                diagnostics)));
        return diagnostics;
    }

    private static void report(String namespace, String id, List<Occurrence> occurrences, Map<Path, String> texts,
                               Function<Path, Path> displayPath, List<Diagnostic> diagnostics) {
        Set<Path> files = new LinkedHashSet<>();
        occurrences.forEach(occurrence -> files.add(occurrence.file()));
        if (files.size() < 2 || occurrences.stream().anyMatch(Occurrence::hasDatabaseId)) {
            return;
        }
        for (Path file : files.stream().sorted().toList()) {
            Occurrence first = occurrences.stream().filter(o -> o.file().equals(file)).findFirst().orElseThrow();
            String others = files.stream().filter(other -> !other.equals(file))
                    .map(other -> displayPath.apply(other).toString().replace('\\', '/'))
                    .sorted().reduce((a, b) -> a + ", " + b).orElse("");
            String xml = texts.computeIfAbsent(file, MapperDuplicateStatementValidator::read);
            diagnostics.add(new Diagnostic(displayPath.apply(file), file.toUri().toString(),
                    idRange(xml, first.rawId()), Severity.ERROR, RuleIds.DUPLICATE_STATEMENT_ID,
                    "Statement id '" + id + "' in namespace '" + namespace + "' is also declared in " + others
                            + "; MyBatis fails at startup."));
        }
    }

    private static Range idRange(String xml, String rawId) {
        Matcher matcher = STATEMENT_TAG.matcher(xml);
        while (matcher.find()) {
            int valueStart = XmlPositions.findAttributeValueStart(xml, matcher.start(), "id", rawId);
            if (valueStart >= 0) {
                return XmlPositions.range(xml, valueStart, valueStart + rawId.length());
            }
        }
        return Range.at(1, 1);
    }

    private static String read(Path file) {
        try {
            return XmlSources.readString(file);
        } catch (Exception e) {
            return "";
        }
    }
}
