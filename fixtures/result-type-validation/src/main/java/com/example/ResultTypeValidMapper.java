package com.example;

/**
 * mybatis-lint/result-type-mismatch の正常系(warning が出ない)を確認する
 * ための Mapper インターフェース。
 */
public interface ResultTypeValidMapper {
    // 単一オブジェクト型の一致: XML 側 resultType="User" と、この戻り値の型
    // User が simple name で一致するため OK。
    User findById(Long id);

    // 完全修飾クラス名の一致: XML 側 resultType="com.example.User" の
    // 末尾 simple name "User" と、この戻り値の型 User が一致するため OK。
    User findByFqcn(Long id);

    // 単純な List<T> の一致: XML 側 resultType="User" と、この戻り値
    // List<User> の要素型 User が一致するため OK。
    java.util.List<User> findAll();
}
