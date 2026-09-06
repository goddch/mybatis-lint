package com.example;

/**
 * mybatis-lint/parameter-type-mismatch の正常系(warning が出ない)を確認する
 * ための Mapper インターフェース。すべて単一引数のメソッドだけを用意している。
 */
public interface ParameterTypeValidMapper {
    // simple name の一致: XML 側 parameterType="User" と、この引数の型 User が
    // simple name で一致するため OK。
    User findByUser(User user);

    // 完全修飾クラス名の一致: XML 側 parameterType="com.example.User" の
    // 末尾 simple name "User" と、この引数の型 User が一致するため OK。
    User findByFqcn(User user);

    // 組み込み alias と primitive の一致: parameterType="long" と
    // primitive の long は自然に対応する。
    User findByPrimitiveLong(long id);

    // 組み込み alias と boxed 型の一致(設計判断):
    // MyBatis の "long" alias は実際には Long.class(boxed)に登録されている。
    // このルールは primitive と boxed の違いを気にせず一致とみなす設計にしている
    // (詳細は MapperParameterTypeValidator のクラス javadoc / EXACT_ALIAS_JAVA_TYPES
    // のコメントを参照)。
    User findByBoxedLong(Long id);

    // 組み込み alias(map)と java.util.Map の一致。
    User findByParamsMap(java.util.Map<String, Object> params);

    // 組み込み alias(list)と java.util.List の一致。
    java.util.List<User> findByIds(java.util.List<Long> ids);
}
