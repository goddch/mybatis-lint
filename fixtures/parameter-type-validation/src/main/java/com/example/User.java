package com.example;

/**
 * parameterType 検証の手動確認用サンプルクラス。
 *
 * <p>mapper XML 側で {@code parameterType="User"} や
 * {@code parameterType="com.example.User"} のように書いたとき、単一引数の
 * Java Mapper メソッドの引数型と simple name が一致するので
 * mybatis-lint/parameter-type-mismatch は報告されない、という正常系の題材にする。</p>
 */
public class User {
    public Long id;
    public String name;
}
