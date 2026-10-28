package com.mybatislint.analyzer.model;

/** Stable identifiers for every diagnostic rule the analyzer can emit. */
public final class RuleIds {
    public static final String MALFORMED_XML = "mybatis-lint/malformed-xml";
    public static final String MISSING_NAMESPACE = "mybatis-lint/missing-namespace";
    public static final String DUPLICATE_STATEMENT_ID = "mybatis-lint/duplicate-statement-id";
    public static final String DUPLICATE_RESULT_MAP_ID = "mybatis-lint/duplicate-result-map-id";
    public static final String DUPLICATE_OTHERWISE = "mybatis-lint/duplicate-otherwise";
    public static final String WHEN_OUTSIDE_CHOOSE = "mybatis-lint/when-outside-choose";
    public static final String OTHERWISE_OUTSIDE_CHOOSE = "mybatis-lint/otherwise-outside-choose";
    public static final String UNDEFINED_PARAMETER = "mybatis-lint/undefined-parameter";
    // <include refid> は MyBatis の SQL 断片再利用で頻出するため、
    // 参照先が静的に見つからないケースを専用 ruleId として区別する。
    public static final String UNRESOLVED_INCLUDE = "mybatis-lint/unresolved-include";
    // resultMap/resultType の同時指定は「壊れている」と断定せず warning にするが、
    // 利用者が意図を整理できるよう専用 ruleId を持たせる。
    public static final String RESULT_MAP_AND_TYPE = "mybatis-lint/result-map-and-type";
    // typeAlias はワークスペース内の情報だけでは完全解決できないため、
    // あくまで「有力な未解決候補」を warning として知らせる専用 ruleId にしている。
    public static final String UNRESOLVED_TYPE_ALIAS = "mybatis-lint/unresolved-type-alias";
    // Java Mapper メソッドの実引数型と、mapper XML 側の parameterType 宣言の
    // ズレを検出する専用 ruleId。unresolved-type-alias が「文字列として怪しい
    // alias 候補か」を見るのに対し、こちらは「Java 側で実際に解決できた引数型と
    // 食い違っていないか」を見る点で責務が異なる(役割分担は
    // MapperParameterTypeValidator のクラス javadoc を参照)。静的解析だけでは
    // 断定しづらいケースが多いため severity は warning にとどめている。
    public static final String PARAMETER_TYPE_MISMATCH = "mybatis-lint/parameter-type-mismatch";
    // parameter-type-mismatch の対になるルール。resultMap を使わない <select
    // resultType="..."> だけを対象に、一意に解決できた Java Mapper メソッドの
    // 戻り値型(単一オブジェクト or 単純な List<T>)と食い違っていないかを見る。
    // resultType は表現の幅が広く誤検知しやすいため、安全に断定できる狭いケースだけに
    // 絞っている(詳細は MapperResultTypeValidator のクラス javadoc を参照)。
    public static final String RESULT_TYPE_MISMATCH = "mybatis-lint/result-type-mismatch";
    public static final String UNKNOWN_RESULT_PROPERTY = "mybatis-lint/unknown-result-property";
    // resultType の自動マッピングで、AS で付けた列の別名に対応するプロパティがないもの。
    // MyBatis はその列を黙って捨てるので、実行時にも気づきにくい。
    public static final String UNKNOWN_SELECT_ALIAS = "mybatis-lint/unknown-select-alias";
    // #{...} で引数の Bean にないプロパティを読もうとしているもの。実行時に
    // ReflectionException(There is no getter for property ...)で失敗する。
    public static final String UNKNOWN_PARAMETER_PROPERTY = "mybatis-lint/unknown-parameter-property";
    // Java の Mapper メソッドに対応する statement が XML にないもの。呼び出すと
    // BindingException(Invalid bound statement)で失敗する。
    public static final String MISSING_MAPPER_STATEMENT = "mybatis-lint/missing-mapper-statement";
    // '%#{name}%' のように SQL の文字列の中に書いた #{...}。バインドされず実行時にエラーになる。
    public static final String PARAMETER_IN_STRING_LITERAL = "mybatis-lint/parameter-in-string-literal";
    // OGNL で 1 文字の '...' は char になり、String と比べても常に等しくならない。
    public static final String SINGLE_CHAR_LITERAL_COMPARISON = "mybatis-lint/single-char-literal-comparison";
    // OGNL で数値と '' を比べると '' が 0 として扱われ、値が 0 のときも条件が成り立たない。
    public static final String NUMERIC_EMPTY_STRING_COMPARISON = "mybatis-lint/numeric-empty-string-comparison";
    // resultMap の column が、それを使う select の SELECT 句にないもの。プロパティは黙って null のままになる。
    public static final String RESULT_MAP_COLUMN_NOT_SELECTED = "mybatis-lint/result-map-column-not-selected";
    public static final String UNRESOLVED_RESULT_MAP_EXTENDS = "mybatis-lint/unresolved-result-map-extends";
    public static final String RESULT_MAP_INHERITANCE_CYCLE = "mybatis-lint/result-map-inheritance-cycle";
    public static final String UNRESOLVED_STATEMENT_RESULT_MAP = "mybatis-lint/unresolved-statement-result-map";
    public static final String UNRESOLVED_NESTED_RESULT_MAP = "mybatis-lint/unresolved-nested-result-map";
    public static final String UNRESOLVED_DISCRIMINATOR_RESULT_MAP = "mybatis-lint/unresolved-discriminator-result-map";
    public static final String DUPLICATE_DISCRIMINATOR_CASE_VALUE = "mybatis-lint/duplicate-discriminator-case-value";

    private RuleIds() {
    }
}
