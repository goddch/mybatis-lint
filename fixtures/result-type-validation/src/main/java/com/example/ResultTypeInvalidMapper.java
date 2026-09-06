package com.example;

/**
 * mybatis-lint/result-type-mismatch の異常系(warning が出る)と
 * フェイルオープン系(あえて報告しない)を確認するための Mapper インターフェース。
 */
public interface ResultTypeInvalidMapper {
    // XML 側 resultType="Order" と、この戻り値の型 User の simple name が
    // 一致しない、典型的な不一致ケース。
    User findById(Long id);

    // XML 側 resultType="Order" と、この戻り値 List<User> の要素型が一致しない。
    java.util.List<User> findAllOrders();

    // resultMap を使う statement 用のメソッド。resultMap がある select は
    // このルールのスコープ外のため、resultType の値に関わらずフェイルオープンする。
    User findWithResultMap(Long id);

    // ${...} を含む動的な resultType は実行時のプロパティ置換で値が変わるため、
    // 静的には判断できずフェイルオープンする。
    User findWithDynamicAlias(Long id);
}

class Order {
}
