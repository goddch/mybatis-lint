# 使われていない定義

`mybatis-lint/unused-sql-fragment`、`mybatis-lint/unused-result-map`、
`mybatis-lint/statement-without-method`(すべて warning)の仕様をまとめたものです。
消し忘れた定義や、名前を変えたときの直し忘れに気づくためのルールです。どれも実行時の
エラーにはならないので warning にしています。

## 何を検証するか

| ルール | 報告するもの | 「使われている」とみなす参照 |
|---|---|---|
| `unused-sql-fragment` | どこからも使われていない `<sql id>` | `<include refid>`(別ファイルからの `namespace.id` も含む) |
| `unused-result-map` | どこからも使われていない `<resultMap id>` | statement の `resultMap`(`a,b` のような複数指定も含む)、`<association>` / `<collection>` / `<case>` の `resultMap`、`<resultMap extends>`、Java の `@ResultMap("id")` / `@ResultMap({"a", "b"})` |
| `statement-without-method` | namespace のインターフェースに同じ名前のメソッドがない statement | インターフェースのメソッド、Java のソースにある `"namespace.id"` / `"id"` という文字列リテラル |

`statement-without-method` は `missing-mapper-statement`(Java のメソッドに statement がない)の
逆向きです。メソッドの名前を変えて XML の id を直し忘れると、両方が出ます。

報告する位置は、定義の `id` の値です。

## 検証しない場合(フェイルオープン)

- **読めない mapper XML**:その namespace の `<sql>` と `<resultMap>` は見ません(壊れたファイルの中の
  参照が読めないため)。namespace もわからない壊れ方なら、ワークスペースのすべての `<sql>` と
  `<resultMap>` を見ません
- **動的な参照**:`refid="${tenant}.columns"` のように namespace だけが動的なら、どの namespace の
  `columns` も使われているとみなします。id の部分が動的(`refid="${name}"`)なら、その種類の定義を
  すべて見ません。`resultMap` も同じです
- **`@ResultMap` の値が文字列リテラルでない**(定数の参照など):`<resultMap>` をすべて見ません
- **namespace のない mapper XML**:MyBatis が読み込めないので、その中の定義も参照も数えません
- **同じ id の定義が複数ある**:どれが使われているか決めないので報告しません(重複は別のルールが報告します)
- **statement の検証**は、namespace と同じ FQCN のインターフェースがワークスペースにただ 1 つあり、
  ほかのインターフェースを extends していない場合だけ行います
  - インターフェースのない namespace は、`sqlSession.selectList("namespace.id")` のように文字列で
    呼ばれます
  - extends していると、メソッドが親(ライブラリのものかもしれない)にあるかもしれません

ワークスペースの外(別のリポジトリや jar)から使われている定義は、使われていないと報告されます。
その場合は、ルールを止めるか、抑制コメントを使ってください。

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/unused-definitions
```

`OrderMapper.xml` に 3 件(`legacyColumns`、`legacyOrderMap`、`findByCustomerName`)、
`OrderMapper.java` に `missing-mapper-statement` が 1 件出ます。
