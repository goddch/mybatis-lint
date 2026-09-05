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

    private RuleIds() {
    }
}
