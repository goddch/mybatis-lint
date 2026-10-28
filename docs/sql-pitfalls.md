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

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/sql-pitfalls
```

`SearchMapper.xml` の `searchInvalid` に、error 1 件と warning 2 件が出ます。
同じ条件を正しく書いた `searchValid` には出ません。
