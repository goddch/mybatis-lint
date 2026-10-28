# SELECT 句の別名と resultType のプロパティの突き合わせ

`mybatis-lint/unknown-select-alias`(warning)の仕様と設計判断をまとめたものです。

## 何を検証するか

`<select resultType="...">` の SELECT 句で `AS` を付けた列の別名が、resultType の
クラスに書き込めるプロパティに対応しているかを見ます。

```xml
<select id="findById" resultType="com.example.UserDomain">
  SELECT u.USER_ID AS userId,
         u.USER_NAME AS userNmae   <!-- userName のタイプミス → warning -->
    FROM users u
</select>
```

MyBatis は resultType の列を自動マッピングしますが、対応するプロパティがない列は
標準の設定(`autoMappingUnknownColumnBehavior=NONE`)では黙って捨てます。値が null の
まま返ってくるだけなので、実行しても気づきにくい誤りです。

## プロパティの決め方

プロパティは `unknown-result-property` と同じく、MyBatis が値を書き込める名前です
([result-map-validation.md](result-map-validation.md) を参照)。

- フィールド(private を含む)と、引数 1 つの `setXxx` から求めた名前
- Lombok(`@Data`、`@Setter` など)のクラスと、ワークスペース内の親クラスのプロパティを含む
- getter だけの名前は、値を書き込めないので含まない

resultType の値からクラスを決めるのは、次の場合だけです。

- 完全修飾クラス名
- `@Alias("...")` で付けた名前
- ワークスペースにただ 1 つしかないクラスの simple name(大文字小文字は無視)

MyBatis の設定ファイルで登録された alias は、指す先を追っていないので検証しません。

## 名前の比べ方

- 大文字小文字は区別しません。MyBatis の自動マッピングは大文字小文字を区別せず、
  Oracle が引用符のない別名を大文字で返しても対応するためです
- アンダースコアは無視します。`mapUnderscoreToCamelCase` が有効なら `user_name` が
  `userName` に対応しますが、この設定は読まないので、どちらの設定でも対応しうる名前は
  警告しません

## 検証しない場合(フェイルオープン)

本格的な SQL パーサーは使わず、SELECT から最初の FROM までを読むだけにしています。
次の場合は列が確定しないので、その statement は検証しません。

- FROM より前に `<include>` や `<if>` などの要素がある
- SELECT 句に `*`、`t.*`、`${...}` がある
- SELECT で始まらない(`WITH` 句など)、FROM が見つからない
- `resultMap` を指定している
- resultType が組み込み alias(`map` など)、`${...}`、決められないクラス

列ごとには、次のものを検証しません。

- `AS` のない列。列名とプロパティ名の対応は `mapUnderscoreToCamelCase` の設定で変わるため
  (`u.NAME userName` のように AS を省いた別名も対象外)
- `"address.street"` のような入れ子のプロパティを指す別名

SQL のコメント(`--`、`/* */`、Oracle のヒント句)と文字列リテラルの中身は読み飛ばし、
括弧の中(関数の引数やサブクエリ)の `AS` や `FROM` は SELECT 句の区切りとして扱いません。

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/select-alias-validation
```

`SelectAliasInvalidMapper.xml` で warning が 2 件出ます(`userNmae` のタイプミスと、
getter しかない `displayName`)。`SelectAliasValidMapper.xml` では出ません。
