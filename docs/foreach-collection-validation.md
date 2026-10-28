# `<foreach collection="...">` の未定義パラメータ検証

`mybatis-lint/undefined-parameter` ルールのうち、`<foreach>` タグの
`collection` 属性の検証について、仕様と設計判断をまとめたものです。
`#{...}` / `${...}` / `<if test>` / `<when test>` も同じルールで検証しています。

## 1. 何を検証するか

MyBatis の `<foreach>` タグは、コレクション(配列・`List`・`Map` など)を
1件ずつ展開して SQL を生成するための仕組みです。

```xml
<foreach collection="ids" item="id" open="(" separator="," close=")">
    #{id}
</foreach>
```

`collection` 属性には、実行時のパラメータオブジェクトに対する式
(単純な識別子、または `request.items` のようなプロパティパス)を書きます。
このルールでは、`collection` 属性の**ルート識別子**
(`request.items` なら `request` の部分)を、`#{...}` などと
まったく同じ基準で検証します。

- `@Param` エイリアス、Java パラメータ名、`param1`/`arg1` などの暗黙名、
  `_parameter`/`_databaseId`、`<bind>` で定義した変数、外側の `<foreach>` の
  `item`/`index` は「有効な参照」として許可する
- それ以外の識別子は「Java Mapper インターフェースの引数として存在しない
  = リファクタリング漏れやタイプミスの可能性が高い」として診断を出す
- Map 系パラメータ・namespace 未解決・メソッド曖昧などのケースは
  **フェイルオープン**(何も報告しない)にする
- `@Param` のない引数が 1 つだけのメソッドは、MyBatis が引数オブジェクトそのものを
  パラメータにする(`#{name}` は Bean のプロパティ、単純型なら値そのものを指す)。
  Bean のプロパティを確かめられるときは `mybatis-lint/unknown-parameter-property` で
  検証し([mapper-binding-validation.md](mapper-binding-validation.md))、確かめられなければ
  検証しない。コレクションと配列の引数は MyBatis が `collection` / `list` / `array` という
  名前で包むので、それらの名前を許可したうえでこのルールで検証する

式の中の次のものは変数参照ではないので、ルート識別子として扱わない。

- `#{item.id,jdbcType=NUMERIC}` や `#{id:NUMERIC}` のカンマ・コロン以降
  (`jdbcType`、`javaType`、`typeHandler` などのマッピング指定)
- `@jp.example.Const@ACTIVE` や `@@abs(x)` のような OGNL の static メンバー参照。
  ただしメソッド呼び出しの引数(`@Util@isEmpty(name)` の `name`)は検証する
- `'ACTIVE'` や `"DONE"` のような文字列リテラルと、`0L`・`1.5d`・`0x1F` のような数値リテラル

## 2. 読むべきファイルの順番

実装を追うときは、次の順で読むと流れがつかみやすいです。

1. `analyzer/src/main/java/com/mybatislint/analyzer/xml/MapperParameterValidator.java`
   - メインの処理。`validate()` メソッドの中で、ステートメントごとに
     `validateForeachCollections(...)` を呼び出しているのを確認してください。
   - `validateForeachCollections` メソッドに、なぜ collection 属性を
     見るのか、スコープ変数(bind/foreach の item・index)をどう扱うかについて
     日本語コメントで詳しく説明しています。
2. `analyzer/src/main/java/com/mybatislint/analyzer/xml/ExpressionRootIdentifierExtractor.java`
   - `#{...}` や `test` 属性の検証で既に使っていた「式からルート識別子だけを
     取り出す」ユーティリティを、`collection` 属性にもそのまま使っています。
3. `analyzer/src/test/java/com/mybatislint/analyzer/xml/MapperParameterValidatorTest.java`
   - テストケース(正常系・異常系・フェイルオープン系)。
     実際にどんな mapper XML / Java インターフェースの組み合わせで
     診断が出る/出ないのかを見るのが一番理解の近道です。

## 3. 設計判断

### 3.1 なぜ `collection` 属性の検証が「保守的」でなければいけないか

MyBatis の `collection` 属性は、単純な識別子だけでなく、OGNL 式として
プロパティパス(`request.items`)やメソッド呼び出しの結果も受け付けます。
本アナライザーは完全な OGNL 評価器ではないため、「ルート識別子だけを見て、
プロパティ以降は無視する」という設計にしています。これにより

- `request.items` → `request` だけをチェックする(items が存在するかは
  見ない)
- 判断に自信が持てない式(メソッド呼び出しを含む、など)は無視する

という形で、**誤検知(false positive)を出すくらいなら何も報告しない**
という方針を徹底しています。Lint ツールは「見逃し」よりも「誤検知」の方が
開発者の信頼を失いやすいため、この非対称性は意図的な設計判断です。

### 3.2 スコープ変数の扱い(既存実装との整合性)

`<foreach>` は `item`/`index` 属性で新しい変数をスコープに導入します。
本来 MyBatis の評価は入れ子(ネスト)構造に従うため、ある `<foreach>` の
`item` はその内側でしか使えません。しかし本アナライザーの
`addScopedNames` は、ステートメント全体で bind/foreach の変数名を
フラットに集約したスコープを使っており、
`validateForeachCollections` もこのスコープをそのまま利用しています。

これは「完全に正確なネスト解析」よりも「実装のシンプルさと誤検知の少なさ」
を優先した結果です。ネスト解析を厳密にやろうとすると、要素の出現順序や
兄弟要素間のスコープリークなど考慮すべき点が急増し、バグの温床になりやすい
ため、既存のフェイルオープン戦略をそのまま踏襲しています。

### 3.3 診断位置(Range)の決め方

診断メッセージのソース上の位置は、`collection` 属性の文字列を XML テキスト
から検索して求めています(`diagnosticForExpression` を再利用)。これは
`test` 属性の検証と全く同じ仕組みで、新しい位置計算ロジックを追加する
必要はありませんでした。既存の仕組みに「乗せる」だけで済んだのは、
`<if test>` / `<when test>` の検証を最初から「式 → ルート抽出 → 診断」という
汎用的なパイプラインとして設計していたおかげです。
