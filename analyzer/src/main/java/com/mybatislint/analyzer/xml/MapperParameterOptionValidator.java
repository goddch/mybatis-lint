package com.mybatislint.analyzer.xml;

import com.mybatislint.analyzer.model.Diagnostic;
import com.mybatislint.analyzer.model.Message;
import com.mybatislint.analyzer.model.RuleIds;
import com.mybatislint.analyzer.model.Severity;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code #{name,jdbcType=VARCHAR}} の指定と、要素の {@code jdbcType} 属性の誤りを見つける。
 * どれも MyBatis が mapper を読み込むとき(または statement を初めて使うとき)に必ず失敗する。
 *
 * <h2>{@code mybatis-lint/invalid-jdbc-type}(error)</h2>
 * <p>{@code jdbcType} の値が {@code org.apache.ibatis.type.JdbcType} にない。MyBatis は
 * {@code JdbcType.valueOf} で読むので、大文字小文字も区別する({@code varchar} は誤り)。
 * {@code mode} の値が IN / OUT / INOUT でないものも同じ扱いにする。</p>
 *
 * <h2>{@code mybatis-lint/unknown-parameter-option}(error)</h2>
 * <p>{@code #{...}} に書けない名前のオプション({@code jdbctype=}、{@code jdbcTyp=} など)。
 * MyBatis は {@code BuilderException: An invalid property ... was found in mapping} で失敗する。</p>
 *
 * <p>{@code javaType}、{@code typeHandler}、{@code resultMap} の値は、設定やクラスパスで決まるので見ない。
 * {@code #{(式)}} のような式の形や、{@code ${...}} を含むものも見ない。</p>
 */
public final class MapperParameterOptionValidator {

    // org.apache.ibatis.type.JdbcType の定数(MyBatis 3.5)。
    static final Set<String> JDBC_TYPES = Set.of(
            "ARRAY", "BIT", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "REAL", "DOUBLE", "NUMERIC",
            "DECIMAL", "CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIME", "TIMESTAMP", "BINARY", "VARBINARY",
            "LONGVARBINARY", "NULL", "OTHER", "BLOB", "CLOB", "BOOLEAN", "CURSOR", "UNDEFINED", "NVARCHAR", "NCHAR",
            "NCLOB", "STRUCT", "JAVA_OBJECT", "DISTINCT", "REF", "DATALINK", "ROWID", "LONGNVARCHAR", "SQLXML",
            "DATETIMEOFFSET", "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE");
    private static final Set<String> MODES = Set.of("IN", "OUT", "INOUT");
    // SqlSourceBuilder が受け付けるオプション。property は無視されるが、エラーにはならない。
    private static final Set<String> OPTIONS = Set.of(
            "javaType", "jdbcType", "mode", "numericScale", "resultMap", "typeHandler", "jdbcTypeName", "property");
    // 警告に並べる一覧。property は MyBatis の内部用なので、利用者には案内しない。
    private static final String OPTION_LIST = String.join(", ",
            List.of("javaType", "jdbcType", "mode", "numericScale", "resultMap", "typeHandler", "jdbcTypeName"));

    private static final Pattern PARAMETER = Pattern.compile("#\\{([^}]*)\\}");
    private static final Pattern JDBC_TYPE_ATTRIBUTE = Pattern.compile("\\sjdbcType\\s*=\\s*([\"'])([^\"']*)\\1");

    public List<Diagnostic> validate(Path actualFile, Path displayFile, String uri) {
        try {
            String xml = XmlSources.readString(actualFile);
            Element mapper = XmlSources.parse(actualFile).getDocumentElement();
            if (mapper == null || !"mapper".equals(mapper.getTagName())) {
                return Collections.emptyList();
            }
            // XML のコメントの中は MyBatis が読まない。位置を変えないよう空白にする。
            String text = blankComments(xml);
            List<Diagnostic> diagnostics = new ArrayList<>();
            Matcher parameter = PARAMETER.matcher(text);
            while (parameter.find()) {
                validateParameter(xml, parameter.start(1), parameter.group(1), displayFile, uri, diagnostics);
            }
            Matcher attribute = JDBC_TYPE_ATTRIBUTE.matcher(text);
            while (attribute.find()) {
                checkValue(xml, attribute.start(2), attribute.group(2), "jdbcType", displayFile, uri, diagnostics);
            }
            // #{...} と属性を別々に探すので、文書の順に並べ直す。
            diagnostics.sort((a, b) -> a.getRange().getStart().getLine() != b.getRange().getStart().getLine()
                    ? Integer.compare(a.getRange().getStart().getLine(), b.getRange().getStart().getLine())
                    : Integer.compare(a.getRange().getStart().getCharacter(), b.getRange().getStart().getCharacter()));
            return diagnostics;
        } catch (Exception e) {
            // XML の壊れ方は MapperXmlAnalyzer の SAX パースが診断する。
            return Collections.emptyList();
        }
    }

    /** {@code name,opt=value,...} を読む。{@code name:VARCHAR} は古い書き方の jdbcType。 */
    private static void validateParameter(String xml, int contentStart, String content, Path displayFile, String uri,
                                          List<Diagnostic> diagnostics) {
        if (content.contains("${") || content.trim().startsWith("(")) {
            return;
        }
        String[] parts = content.split(",", -1);
        int offset = contentStart;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                int colon = part.indexOf(':');
                if (colon >= 0) {
                    checkValue(xml, offset + colon + 1, part.substring(colon + 1), "jdbcType", displayFile, uri,
                            diagnostics);
                }
            } else {
                int equals = part.indexOf('=');
                String name = (equals < 0 ? part : part.substring(0, equals)).trim();
                if (name.isEmpty()) {
                    // #{x,} のような空のオプションを MyBatis がどう読むかは確かめられないので見ない。
                    offset += part.length() + 1;
                    continue;
                }
                if (equals < 0 || !OPTIONS.contains(name)) {
                    int nameStart = offset + part.indexOf(name);
                    String suggestion = NameSuggestions.closest(name, OPTIONS);
                    diagnostics.add(new Diagnostic(displayFile, uri,
                            XmlPositions.range(xml, nameStart, nameStart + name.length()), Severity.ERROR,
                            RuleIds.UNKNOWN_PARAMETER_OPTION,
                            Message.of("'" + name + "' is not an option of #{...}; MyBatis accepts " + OPTION_LIST + ".",
                                    "'" + name + "' は #{...} に書けるオプションではありません。書けるのは "
                                            + OPTION_LIST + " です。")
                                    .plus(NameSuggestions.didYouMean(suggestion)),
                            suggestion));
                } else if ("jdbcType".equals(name) || "mode".equals(name)) {
                    checkValue(xml, offset + equals + 1, part.substring(equals + 1), name, displayFile, uri, diagnostics);
                }
            }
            offset += part.length() + 1;
        }
    }

    private static void checkValue(String xml, int valueStart, String rawValue, String option, Path displayFile,
                                   String uri, List<Diagnostic> diagnostics) {
        String value = rawValue.trim();
        Set<String> allowed = "mode".equals(option) ? MODES : JDBC_TYPES;
        if (value.isEmpty() || value.contains("${") || allowed.contains(value)) {
            return;
        }
        int start = valueStart + rawValue.indexOf(value);
        String suggestion = allowed.contains(value.toUpperCase(Locale.ROOT))
                ? value.toUpperCase(Locale.ROOT)
                : NameSuggestions.closest(value, allowed);
        Message message = "mode".equals(option)
                ? Message.of("mode must be IN, OUT or INOUT, not '" + value + "'.",
                        "mode に書けるのは IN、OUT、INOUT のどれかです('" + value + "' は書けません)。")
                : Message.of("'" + value + "' is not a JDBC type MyBatis knows; it reads jdbcType with JdbcType.valueOf, "
                                + "which is case-sensitive.",
                        "'" + value + "' は MyBatis が知っている JDBC の型ではありません(jdbcType は JdbcType.valueOf で"
                                + "読むので、大文字と小文字も区別されます)。");
        diagnostics.add(new Diagnostic(displayFile, uri, XmlPositions.range(xml, start, start + value.length()),
                Severity.ERROR, RuleIds.INVALID_JDBC_TYPE, message.plus(NameSuggestions.didYouMean(suggestion)),
                suggestion));
    }

    private static String blankComments(String xml) {
        StringBuilder text = new StringBuilder(xml);
        Matcher comment = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(xml);
        while (comment.find()) {
            for (int i = comment.start(); i < comment.end(); i++) {
                if (text.charAt(i) != '\n') {
                    text.setCharAt(i, ' ');
                }
            }
        }
        return text.toString();
    }
}
