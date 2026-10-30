# INSERT の列と値の対応

`mybatis-lint/insert-column-count`(error)と、VS Code 拡張の列名の表示の
仕様をまとめたものです。

## `insert-column-count`

`INSERT INTO t (列, ...)` の列の数と、`VALUES (...)` の値の数、または `SELECT` 句の列の数が
合わないものを報告します。Oracle では ORA-00947(値が足りない)/ ORA-00913(値が多すぎる)で
必ず失敗します。報告する位置は `INSERT` です。

数えるときは、括弧の中(関数の引数やサブクエリ)、文字列リテラル、`#{...}`(`jdbcType` などを
カンマで続けて書ける)、SQL のコメントの中のカンマを区切りとみなしません。

### 検証しない場合(フェイルオープン)

- statement の先頭から最初の子要素(`<if>`、`<include>`、`<foreach>`、`<trim>` など)までのテキストで、
  列の一覧と値を読み切れない(MyBatis が出力する列や値が、実行時の値で変わる)
- 列の一覧を書いていない INSERT
- `${...}` がある、SELECT 句に `*` や `t.*` がある、SELECT 句のあとに FROM がない

## 列名の表示(VS Code 拡張)

`<insert>` の中で、`VALUES (...)` の各値と `SELECT` 句の各項目に対応する列の名前を、
その値が始まる行の末尾に薄く表示します。1 行に値が複数あれば、列の名前を `, ` でつないで表示します。
行の先頭に表示すると列の名前の長さで SQL のインデントがずれるため、末尾に出し、1 つの INSERT の中では
表示の開始位置をそろえます(タブはエディタのタブ幅で、全角文字は 2 文字分として長さを数えます)。

```
SELECT o.ORDER_ID,            ORDER_ID
       o.NAME,                CUSTOMER_NAME
       NVL(o.AMOUNT, 0), 'x'  AMOUNT, NOTE
```

列が多い INSERT ... SELECT で、どの値がどの列に入るのかを追いやすくするためのもので、
ファイルは書き換えません。

- 拡張機能が開いているファイルを直接読むので、保存しなくても入力に合わせて変わります
- 設定 `mybatisLint.insertColumnHints`
  - `all`(既定)— すべての値に表示する
  - `differentNames` — 値の名前が列名と違うものだけに表示する(`#{orderId}` や `o.ORDER_ID` は
    `ORDER_ID` と同じ名前とみなし、大文字小文字とアンダースコアは無視する)。順番がずれていると、
    ずれたところから表示が出るので気づけます
  - `off` — 表示しない
- 値の数が列より少なければ、値のある列だけに表示します(数の違いは `insert-column-count` が報告します)
- 表示しない INSERT の条件は、`insert-column-count` と同じです
- インレイヒントではなく行末の装飾(decoration)で表示します。インレイヒントは位置をそろえるための空白にも
  背景色が付き、テーマによっては帯のように見えるためです

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/insert-column-count
```

`InsertColumnCountInvalidMapper.xml` で error が 2 件出ます。`InsertColumnCountValidMapper.xml` では出ません。
