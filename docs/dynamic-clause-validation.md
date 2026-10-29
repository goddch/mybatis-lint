# `<where>` / `<set>` を使わない動的 SQL

`mybatis-lint/dangling-sql-joiner`(error / warning)と
`mybatis-lint/empty-dynamic-clause`(warning)の仕様と設計判断をまとめたものです。

## 何を検証するか

SQL のテキストに `WHERE` / `SET` を書き、そのあとに `<if>` で条件や項目を足していると、
どの `<if>` が成り立つかによって SQL が壊れます。特定の引数の組み合わせでしか壊れないので、
テストで見逃しやすい不具合です。

```xml
<!-- name を渡すと WHERE AND name = ? になる -->
WHERE
<if test="name != null">AND name = #{name}</if>

<!-- mail を渡すと SET ..., mail = ?, WHERE になる -->
UPDATE users SET
<if test="name != null">name = #{name},</if>
<if test="mail != null">mail = #{mail},</if>
WHERE id = #{id}
```

`WHERE` / `SET` のあとを、テキスト(必ず出る)と `<if>`(出ないことがある)の並びとして読み、
次のものを報告します。

| 内容 | 重大度 |
|---|---|
| 句の先頭に来うる `<if>` が `AND` / `OR`(SET ならカンマ)で始まる。その `<if>` が句の最初なら | error |
| 同じだが、前にある `<if>` がすべて成り立たないときだけ先頭に来る | warning |
| 句の末尾に来うる `<if>` が `AND` / `OR` / カンマで終わる。その `<if>` が句の最後なら | error |
| 同じだが、後ろにある `<if>` がすべて成り立たないときだけ末尾に来る(テキストの `status = 1 AND` のあとが `<if>` だけ、なども含む) | warning |
| 句の中身がすべて `<if>` で、全部が成り立たないと `WHERE` / `SET` のあとが空になる(`empty-dynamic-clause`) | warning |

error は、その `<if>` が成り立てば必ず壊れるものです。warning は条件の組み合わせによるもので、
呼び出し側が必ずどれかを渡すなど、実際には起きないこともあります。

直し方は `<where>` / `<set>` を使うことです。`<where>` は先頭の `AND` / `OR` を取り除き、
中身がなければ `WHERE` ごと出しません。`<set>` は先頭と末尾のカンマを取り除きます。
`<where>` は末尾の `AND` / `OR` は取り除かないので、`AND` は各条件の先頭に書きます。

`<choose>` は、`<otherwise>` に中身があれば必ず何かを出すのでテキストと同じに扱い、
なければ `<if>` と同じに扱います。どの `<when>` / `<otherwise>` の先頭・末尾も見ます。

## 句の終わり

WHERE 句は `ORDER BY`、`GROUP BY`、`HAVING`、`UNION`、`MINUS`、`FOR UPDATE`、`FETCH` などか、
対応のない閉じ括弧(サブクエリの終わり)で終わります。SET 句は `WHERE` で終わります。
statement の終わりも句の終わりです。

## 検証しない場合(フェイルオープン)

- 間に `<include>`、`<foreach>`、`<trim>`、`<where>`、`<set>` などがあると、そこから先は
  中身や空になるかがわからないので読みません(それより前で見つけたものは報告します)
- `<sql>` や `<if>` の中で終わる WHERE は、続きが外にあるので、末尾と空になるかは検証しません
- `<if>` の先頭や末尾が子要素(入れ子の `<if>` など)のときは、その側を見ません
- `WHERE` を書いたテキストの中で句が終わっている(静的な SQL)
- SQL のコメントと文字列リテラルの中は読み飛ばします
- 括弧の中(サブクエリ)の WHERE が同じテキストの中で閉じていない場合など、
  読み取りが複雑になるものは対象にしません

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/dynamic-clause
```

`DynamicClauseInvalidMapper.xml` で error が 1 件、warning が 4 件出ます。
`DynamicClauseValidMapper.xml` では出ません。
