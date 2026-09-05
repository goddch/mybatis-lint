package com.example;

/**
 * typeAlias 検証の手動確認用サンプルクラス。
 *
 * <p>MyBatis はデフォルトで、Java クラスの simple name(パッケージなしの名前)を
 * そのまま typeAlias として使えるようにする。mapper XML 側で {@code type="User"}
 * のように書いたとき、このクラスがワークスペース内に見つかるので
 * mybatis-lint/unresolved-type-alias は報告されない、という正常系の題材にする。</p>
 */
public class User {
    public Long id;
    public String name;
}
