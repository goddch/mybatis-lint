package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.javaindex.AutoMappingConstructor;
import com.mybatislint.analyzer.javaindex.JavaTypeIndex;
import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Range;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引数なしのコンストラクタがないクラスを {@code <select resultType>} に書いたときの、
 * コンストラクタへの位置による値の渡し方を見る。
 *
 * <p>MyBatis は resultType のクラスに引数なしのコンストラクタがないと、SELECT 句の列を
 * 先頭から順にコンストラクタの引数へ渡す(列の名前は見ない)。そのあと、名前が一致する列は
 * プロパティにも書き込む(final のフィールドにも直接書き込む)。Lombok の {@code @Data} で
 * すべてのフィールドを final にしたクラスがこれにあたる。</p>
 *
 * <h2>{@code mybatis-lint/constructor-column-count}(error)</h2>
 * <p>列の数がコンストラクタの引数より少ない。実行時に必ず失敗する。</p>
 *
 * <h2>{@code mybatis-lint/constructor-positional-binding}(warning)</h2>
 * <p>Lombok が作るコンストラクタで、名前が一致する列のないフィールドがある。そのフィールドには、
 * 同じ位置にある別の列の値が入ったまま残る。列の順番がたまたま合っていると正しく見えるので、
 * 別名の打ち間違いがテストで見つからない。</p>
 *
 * <h2>判断できない場合は何もしない</h2>
 * <ul>
 *   <li>SELECT 句の列が確定しない({@link MapperSelectAliasValidator} と同じ読み方)</li>
 *   <li>resultType のクラスを決められない、コンストラクタが 2 つ以上ある、Lombok の
 *       {@code @Builder} など作られるコンストラクタをソースから決められない</li>
 *   <li>位置による警告は、ソースに書かれたコンストラクタには出さない(本体で引数を
 *       どのフィールドに入れるかわからない)</li>
 * </ul>
 * <p>名前は {@link MapperSelectAliasValidator} と同じく、大文字小文字とアンダースコアを無視して比べる。</p>
 */
public final class MapperConstructorMappingValidator {

    private final JavaTypeIndex typeIndex;

    public MapperConstructorMappingValidator(JavaTypeIndex typeIndex) {
        this.typeIndex = typeIndex == null ? JavaTypeIndex.empty() : typeIndex;
    }

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        if (typeIndex.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            List<Diagnostic> diagnostics = new ArrayList<>();
            int searchOffset = 0;
            NodeList children = mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element select) || !"select".equals(select.getTagName())) {
                    continue;
                }
                int elementStart = MapperSelectAliasValidator.findSelectStart(xml, select, searchOffset);
                if (elementStart < 0) {
                    break;
                }
                searchOffset = elementStart + "<select".length();
                validateSelect(select, xml, elementStart, displayFile, uri, diagnostics);
            }
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    private void validateSelect(Element select, String xml, int elementStart, Path displayFile, String uri,
                                List<Diagnostic> diagnostics) {
        if (!select.getAttribute("resultMap").isBlank()) {
            return;
        }
        String resultType = select.getAttribute("resultType");
        if (resultType.isBlank() || resultType.contains("${")) {
            return;
        }
        String qualifiedName = typeIndex.resolveTypeReference(resultType.trim());
        AutoMappingConstructor constructor = typeIndex.findAutoMappingConstructor(qualifiedName);
        if (constructor == null) {
            return;
        }
        List<String> labels = MapperSelectAliasValidator.extractSelectLabels(MapperSelectAliasValidator.leadingSql(select));
        if (labels == null) {
            return;
        }
        Range range = XmlPositions.rangeForAttributeValue(xml, "resultType", resultType, elementStart);
        if (labels.size() < constructor.parameterCount()) {
            diagnostics.add(new Diagnostic(displayFile, uri, range, Severity.ERROR, RuleIds.CONSTRUCTOR_COLUMN_COUNT,
                    "'" + qualifiedName + "' has no no-arg constructor, so MyBatis passes the columns to its "
                            + constructor.parameterCount() + "-argument constructor by position, but this SELECT "
                            + "returns only " + labels.size() + " column(s)."));
            return;
        }
        if (constructor.fieldNames() == null) {
            return;
        }
        Set<String> normalizedLabels = new HashSet<>();
        labels.forEach(label -> normalizedLabels.add(MapperSelectAliasValidator.normalize(label)));
        for (int i = 0; i < constructor.fieldNames().size(); i++) {
            String field = constructor.fieldNames().get(i);
            if (normalizedLabels.contains(MapperSelectAliasValidator.normalize(field))) {
                continue;
            }
            diagnostics.add(new Diagnostic(displayFile, uri, range, Severity.WARNING,
                    RuleIds.CONSTRUCTOR_POSITIONAL_BINDING,
                    "No column is named '" + field + "', so '" + qualifiedName + "." + field
                            + "' gets the value of column '" + labels.get(i) + "' only because of its position "
                            + "in the constructor; alias the column as '" + field + "', or add @NoArgsConstructor."));
        }
    }
}
