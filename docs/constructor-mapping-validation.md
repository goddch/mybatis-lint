# 引数なしのコンストラクタがない resultType

`mybatis-lint/constructor-column-count`(error)と
`mybatis-lint/constructor-positional-binding`(warning)の仕様と設計判断をまとめたものです。

## MyBatis の動き

`<select resultType="...">` のクラスに引数なしのコンストラクタがないと、MyBatis は
次の順に値を入れます。

1. SELECT 句の列を**先頭から順に**、コンストラクタの引数へ渡す(列の名前は見ない)。
   コンストラクタがただ 1 つならそれを使う
2. そのあと自動マッピングで、名前が一致する列をプロパティにも書き込む。setter がなければ
   フィールドに直接書き込むので、`final` のフィールドにも書き込まれる

Lombok の `@Data` ですべてのフィールドを `final` にすると、引数なしのコンストラクタも setter も
作られず、フィールドを宣言順に受け取るコンストラクタだけが作られるので、この動きになります。

そのため、名前が一致する列のないフィールドには、1 で同じ位置にあった別の列の値が残ります。
別名を打ち間違えても、列の順番がフィールドの順番とたまたま同じなら正しい値に見え、
テストでは見つかりません。列の順番を変えたり列を足したりすると、値がずれます。

根本的には、引数なしのコンストラクタを用意すれば(`@NoArgsConstructor(force = true)` など)、
MyBatis は列を名前だけで書き込むので、順番に左右されなくなります。

## 何を検証するか

- **`constructor-column-count`(error)** 列の数がコンストラクタの引数より少ない。
  実行時に必ず失敗します
- **`constructor-positional-binding`(warning)** Lombok が作るコンストラクタで、
  名前が一致する列のないフィールド。どの列の値が入ってしまうかをメッセージに書きます

どちらも `resultType` の属性値の位置に報告します。列の順番がフィールドと違うだけなら、
すべてのフィールドに名前の一致する列がある限り、位置で入った値は名前で上書きされるので
警告しません(列の型がコンストラクタの引数の型に合わないと実行時に失敗することがありますが、
型は静的に決められないので見ません)。列がコンストラクタの引数より多いのは問題ありません。

名前は `unknown-select-alias` と同じく、大文字小文字とアンダースコアを無視して比べます。

## コンストラクタの決め方

ソースに書かれたコンストラクタと、Lombok の注釈から作られるコンストラクタを数えます。

- `@AllArgsConstructor` — static でないすべてのフィールド(初期値のある final フィールドを除く)
- `@RequiredArgsConstructor` — 初期値のない final フィールドと `@NonNull` のフィールド
- `@Data` — コンストラクタを書いておらず、Lombok のコンストラクタの注釈もなければ
  `@RequiredArgsConstructor` と同じもの
- `@Value` — 同じ条件で、すべてのフィールドを final とした `@AllArgsConstructor` と同じもの

コンストラクタが 1 つもなければ、コンパイラが引数なしのコンストラクタを作ります。

## 検証しない場合(フェイルオープン)

- 引数なしのコンストラクタがある(`@NoArgsConstructor` を含む)、コンストラクタが 2 つ以上ある
  (MyBatis は列の型や `@AutomapConstructor` で選ぶ)、可変長引数のコンストラクタ
- `@Builder`、`@SuperBuilder`、`@FieldDefaults`、`@NonFinal`、`@Tolerate` のどれかがある
  (作られるコンストラクタやフィールドの final がソースから決まらない)
- ワークスペースのどこかの `lombok.config` に `lombok.noArgsConstructor.extraPrivate = true` がある
  (`@Data` / `@Value` のクラスに private の引数なしコンストラクタが足される)
- 抽象クラス、インターフェース、record、resultType のクラスを一意に決められない
- `resultMap` を使う select、SELECT 句の列が確定しない select(`*`、FROM より前の `<include>` や
  `<if>` など。`unknown-select-alias` と同じ読み方)
- 位置による警告は、ソースに書かれたコンストラクタには出しません。本体で引数をどのフィールドに
  入れるかわからないためです(列の数は見ます)

MyBatis の設定 `argNameBasedConstructorAutoMapping` を有効にしていると、列は名前でコンストラクタに
渡されます。その場合も、名前の一致する列がない引数があると失敗するので、警告は役に立ちます。

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/constructor-mapping
```

`ConstructorMappingInvalidMapper.xml` で warning が 2 件(`custmerName` のタイプミスに対する
`unknown-select-alias` と `constructor-positional-binding`)、error が 1 件
(`constructor-column-count`)出ます。`ConstructorMappingValidMapper.xml` では出ません。
