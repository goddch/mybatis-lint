# SQL と `<if test>` のよくある落とし穴

MyBatis でよく起きるのに、見ただけでは気づきにくい誤りを見つける 3 つのルールの
仕様と設計判断をまとめたものです。

## `mybatis-lint/parameter-in-string-literal`(error)

SQL の文字列リテラル(`'...'`)の中に書いた `#{...}` を報告します。

```xml
WHERE NAME LIKE '%#{name}%'             <!-- error -->
WHERE NAME LIKE '%' || #{name} || '%'   <!-- 正しい書き方 -->
```

MyBatis は `#{name}` を `?` に置き換えますが、文字列の中の `?` はバインド変数として
扱われません。そのため実行時に、パラメータの位置が不正だというエラーになります
(Oracle なら invalid column index)。`${...}` は SQL にそのまま埋め込まれるので、
文字列の中に書いても動きます(このルールの対象外です)。

対象は `<select>` / `<insert>` / `<update>` / `<delete>` と `<sql>` の中の SQL です。
SQL のコメント(`--`、`/* */`)の中の `'` は読み飛ばし、`''`(文字列の中の引用符)にも
対応しています。次の場合は読み違えの可能性があるので、その statement は検証しません。

- 引用符の対応がとれない
- Oracle の `q'[...]'` を使っている

## `mybatis-lint/single-char-literal-comparison`(warning)

`<if test>` / `<when test>` で、1 文字の単引用符と比べている式を報告します。

```xml
<if test="type == 'A'">            <!-- warning: 常に false -->
<if test='type == "A"'>            <!-- 正しい書き方 -->
<if test="type == 'A'.toString()"> <!-- これも正しい -->
```

OGNL では `'A'` のような 1 文字の単引用符は文字(char)になり、String の `"A"` とは
等しくなりません。そのため条件が絶対に成り立たず、エラーにもならないので気づきにくい
誤りです。`==`、`!=`、`eq`、`neq`、`.equals('A')` を対象にします。2 文字以上の
`'AB'` は String になるので対象外です。

比べる相手が char / Character だとわかる場合(引数の型や、引数の Bean のフィールドの型)は
正しいので警告しません。相手の型がわからない場合は警告します。char 型の値を 1 文字の
単引用符と比べる書き方はまれで、この誤りのほうがずっと多いためです。

## `mybatis-lint/numeric-empty-string-comparison`(warning)

`<if test>` / `<when test>` で、数値と `''` を比べている式を報告します。

```xml
<if test="status != null and status != ''">  <!-- warning: status が 0 のときも false -->
<if test="status != null">                   <!-- 正しい書き方 -->
```

OGNL では数値と `''` を比べると `''` が 0 として扱われるので、`status != ''` は status が
0 のときに false になります。String 用の書き方を数値に流用すると起きる誤りです。

比べる相手の型が数値型(int、Integer、long、BigDecimal など)だとわかる場合だけ警告します。
型は、引数の型か、引数の Bean のフィールドの型から決めます。`<bind>` や `<foreach>` の
変数と同じ名前や、型がわからない場合は警告しません。

## `mybatis-lint/unsafe-dollar-substitution`(warning)

```xml
WHERE name = '${name}'   <!-- name に ' OR '1' = '1 を渡すと条件が書き換わる -->
```

`${...}` は値を SQL の文字列としてそのまま埋め込みます。利用者が入力した値が入ると、SQL そのものを
書き換えられます(SQL インジェクション)。`#{...}` はバインド変数になるので安全です。

`ORDER BY ${column}` やテーブル名のように `#{...}` では書けない場所もあるので、値を書く場所だと
わかるものだけを報告します。

| 場所 | 直し方 |
|---|---|
| 比較演算子(`=`、`<>`、`!=`、`<`、`>`、`<=`、`>=`、`&lt;` などの実体参照も)や `LIKE` のすぐあと | `#{name}` に置き換える(クイックフィックスあり) |
| `'%${name}%'` のような文字列リテラルの中 | 引用符の外に出して `'%' \|\| #{name} \|\| '%'` |
| `IN (${ids})` の括弧のすぐ中 | `<foreach>` と `#{item}` でリストを作る |

次の場合は報告しません。

- 値の型が数値(int、Long、BigDecimal など)や boolean だとわかる。SQL を書き換える文字列にならないため。
  型は `numeric-empty-string-comparison` と同じく、引数の型か引数の Bean のフィールドの型から決めます
- `${@com.example.Const@VALUE}` のような定数の参照
- SQL のコメントの中、`<if test>` などの属性の中(OGNL で、SQL ではない)

## `mybatis-lint/invalid-jdbc-type`、`mybatis-lint/unknown-parameter-option`(error)

```xml
#{name,jdbcType=varchar}   <!-- JdbcType.valueOf は大文字小文字を区別する -->
#{id, jdbctype=NUMERIC}    <!-- オプション名の誤り -->
```

MyBatis は `jdbcType` の値を `JdbcType.valueOf` で読むので、`JdbcType` にない値や小文字の値は、
mapper を読み込むときに失敗します。`#{...}` に書けるオプションは `javaType`、`jdbcType`、`mode`、
`numericScale`、`resultMap`、`typeHandler`、`jdbcTypeName` だけで、ほかの名前は
`BuilderException` になります。

- `#{name,jdbcType=...}`、古い書き方の `#{name:TYPE}`、`<result jdbcType="...">` などの属性を見ます
- `mode` は IN / OUT / INOUT のどれかでなければ報告します
- 正しい値が 1 つに決まるとき(大文字にすれば正しい、1〜2 文字の違い)は、クイックフィックスで直せます
- `javaType`、`typeHandler`、`resultMap` の値は、設定やクラスパスで決まるので見ません。`${...}` を含む値や、
  `#{(式)}` の形、XML のコメントの中も見ません

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/sql-pitfalls
```

`SearchMapper.xml` の `searchInvalid` に、error 1 件と warning 2 件が出ます。
同じ条件を正しく書いた `searchValid` には出ません。

`${...}` の警告は `fixtures/dollar-substitution` で確認できます。`ProductMapper.xml` の
`searchInvalid` に warning が 3 件出て、`searchValid` には出ません。

jdbcType とオプションの誤りは `fixtures/parameter-options` で確認できます。`findInvalid` と `orderMap` に
error が 4 件出て、`findValid` には出ません。
