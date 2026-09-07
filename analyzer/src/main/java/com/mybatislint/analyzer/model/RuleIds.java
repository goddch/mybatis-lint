package com.mybatislint.analyzer.model;

/** Stable identifiers for every diagnostic rule the analyzer can emit. */
public final class RuleIds {
    public static final String MALFORMED_XML = "mybatis-lint/malformed-xml";
    public static final String MISSING_NAMESPACE = "mybatis-lint/missing-namespace";
    public static final String DUPLICATE_STATEMENT_ID = "mybatis-lint/duplicate-statement-id";
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
    // クレジット節約のため、今回はこの極めて狭いケースだけに絞っている
    // (詳細は MapperResultTypeValidator のクラス javadoc を参照)。
    public static final String RESULT_TYPE_MISMATCH = "mybatis-lint/result-type-mismatch";

    private RuleIds() {
    }
}
