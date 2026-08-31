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
    public static final String UNRESOLVED_INCLUDE = "mybatis-lint/unresolved-include";
    public static final String RESULT_MAP_AND_TYPE = "mybatis-lint/result-map-and-type";

    private RuleIds() {
    }
}
