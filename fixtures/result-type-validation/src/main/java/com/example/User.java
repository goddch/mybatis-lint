package com.example;

/**
 * resultType 検証の手動確認用サンプルクラス。
 *
 * <p>mapper XML 側で {@code resultType="User"} や
 * {@code resultType="com.example.User"} のように書いたとき、一意に解決できる
 * Java Mapper メソッドの戻り値の simple name が一致するので
 * mybatis-lint/result-type-mismatch は報告されない、という正常系の題材にする。</p>
 */
public class User {
    public Long id;
    public String name;
}
