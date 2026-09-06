package com.example;

/**
 * mybatis-lint/parameter-type-mismatch の異常系(warning が出る)と
 * フェイルオープン系(あえて報告しない)を確認するための Mapper インターフェース。
 */
public interface ParameterTypeInvalidMapper {
    // XML 側 parameterType="Order" と、この引数の型 User の simple name が
    // 一致しない、典型的な不一致ケース。
    User findByUser(User user);

    // XML 側 parameterType="string" と、この引数の型 User は
    // 組み込み alias "string" が期待する String と全く異なるため不一致。
    User findByObject(User user);

    // 複数引数メソッド: このルールは単一引数メソッドだけを対象にするため、
    // parameterType の値に関わらずフェイルオープンする(warning は出ない)。
    User findByNameAndStatus(String name, String status);

    // ${...} を含む動的な parameterType は実行時のプロパティ置換で値が
    // 変わるため、静的には判断できずフェイルオープンする。
    User findWithDynamicAlias(User user);
}

class Order {
}
