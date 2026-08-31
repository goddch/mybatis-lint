# `<include refid="...">` 参照検証と `resultMap`/`resultType` 警告(学習メモ)

このドキュメントは、`mybatis-lint/unresolved-include` と
`mybatis-lint/result-map-and-type` を追加した変更を読むためのガイドです。

## 実装を読む順番

1. [`MapperSqlFragmentIndex`](../analyzer/src/main/java/com/mybatislint/analyzer/xml/MapperSqlFragmentIndex.java)
   - ワークスペース内の `<mapper namespace="...">` と `<sql id="...">` を集める索引です。
2. [`AnalyzerCli`](../analyzer/src/main/java/com/mybatislint/analyzer/cli/AnalyzerCli.java)
   - 各 XML を解析する前に SQL fragment の索引を作り、全ファイルの解析に渡します。
3. [`MapperReferenceValidator`](../analyzer/src/main/java/com/mybatislint/analyzer/xml/MapperReferenceValidator.java)
   - `<include refid>` が解決できるか、`resultMap` と `resultType` が同時指定されていないかを見ます。
4. [`MapperReferenceValidatorTest`](../analyzer/src/test/java/com/mybatislint/analyzer/xml/MapperReferenceValidatorTest.java)
   - 正常系、異常系、動的 refid のフェイルオープンを具体例で確認できます。
5. [`fixtures/include-validation`](../fixtures/include-validation/)
   - CLI や VS Code 拡張で手動確認するための XML サンプルです。

## `<sql>` / `<include>` の解決ルール

MyBatis では、再利用したい SQL 断片を次のように定義できます。

```xml
<sql id="BaseColumns">id, name</sql>
```

同じ mapper ファイルの中なら、短い ID で参照できます。

```xml
<include refid="BaseColumns" />
```

別 mapper にある SQL 断片を参照したい場合は、通常
`namespace.sqlId` の完全修飾名で書きます。

```xml
<include refid="com.example.CommonFragments.BaseColumns" />
```

このため、今回の実装では次のように判定しています。

- `refid` にドットがない場合: 同じ mapper ファイル内の `<sql id>` だけを探す
- `refid` が現在の `namespace.sqlId` 形式の場合: 同じ mapper ファイル内の `<sql id>` と照合する
- `refid` が別 `namespace.sqlId` 形式の場合: ワークスペース全体から作った索引で照合する

namespace 自体にもドットが入るため、索引では namespace を長い順に見ています。
これにより、`com.example.UserMapper.BaseColumns` を誤って `com.example` +
`UserMapper.BaseColumns` のように短く切ってしまうことを避けています。

## 動的 refid をフェイルオープンする理由

次のような `refid` は、MyBatis のプロパティ置換で実行時に値が決まります。

```xml
<include refid="${tenantPrefix}.Columns" />
```

静的解析の時点では `${tenantPrefix}` の具体的な値が分かりません。
ここで「解決できない」と報告すると、実行時には正しく解決できるコードまで
誤検知する可能性があります。そのため、本プロジェクトの方針である
「誤検知を出すくらいなら報告しない」に従い、`${...}` を含む `refid` は
診断しない設計にしています。

## `resultMap` と `resultType` の同時指定

`resultMap` と `resultType` は、どちらも `<select>` などの結果を Java 側へ
どうマッピングするかを表す属性です。両方を同時に書くと、読み手にとって
「どちらの方針でマッピングしたいのか」が曖昧になります。

このルールは実行不能な XML とまでは言い切らないため、`error` ではなく
`warning` として報告します。診断位置は、利用者がすぐ直せるように
`resultMap` 属性値の近くへ出しています。
