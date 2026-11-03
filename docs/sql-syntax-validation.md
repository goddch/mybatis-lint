# SQL の構文チェック(`mybatis-lint/sql-syntax-error`)

mapper XML の statement を、MyBatis と同じ規則でタグのない SQL に組み立て、接続先の DB の文法として
読めるかを確かめます。読めなかった箇所を warning として、元の XML の位置で報告します。

```xml
<select id="findAll" resultType="map">
  SELECT ID, NAME
    FORM USERS          <!-- SQL syntax error near 'USERS' (checked as Oracle SQL). -->
</select>
```

DB に接続することはありません。パーサーには [JSqlParser](https://github.com/JSQLParser/JSqlParser) を
使います(選んだ理由は [`sql-parser-evaluation.md`](sql-parser-evaluation.md))。

## どの DB の文法で読むか

違う DB の文法で読むと、正しい SQL まで誤りになります。そのため、**DB が 1 つに決まったときだけ**、
その DB の文法で読みます。

1. 設定 `mybatisLint.sqlDialect`(CLI では `--sql-dialect`)が `oracle` / `postgresql` / `mysql` なら、その DB。
   `off` ならチェックしない
2. statement に `databaseId` があり、それが `oracle` / `postgresql`(`postgres`)/ `mysql` なら、その DB。
   それ以外の `databaseId`(`db2`、`h2` など)の statement は読まない
3. 設定が `auto`(既定)なら、ワークスペースの次のファイルから推定する
   - `*.properties`、`*.yml`、`*.yaml`(Spring Boot の `spring.datasource.url` など)
   - XML(mybatis-config.xml の `driver` / `url`、`pom.xml` の依存)
   - `build.gradle`、`build.gradle.kts` の依存

   探すのは JDBC の URL(`jdbc:oracle:`、`jdbc:postgresql:`、`jdbc:mysql:`)、ドライバのクラス名
   (`oracle.jdbc.`、`org.postgresql`、`com.mysql`)、ドライバの依存(`ojdbc`、`mysql-connector` など)。
   **見つかった DB が 1 つだけのとき**だけ、その DB とみなします。見つからないとき、2 つ以上見つかったとき
   (本番は Oracle で一部だけ PostgreSQL、など)は推定せず、2 の `databaseId` がある statement だけを読みます。
   H2 や HSQLDB はテスト用に入っていることが多いので、手がかりにしません。

どの DB として読んだか、何件の statement を読めたかは、拡張機能のログ(**MyBatis Lint: Show Log**)に出ます。

```text
SQL syntax check: Oracle (detected from pom.xml). Checked 120 statement(s), skipped 15 (...).
```

JSqlParser は 1 つの文法で各 DB の書き方をまとめて受け付けるので、Oracle の `(+)` や `ROWNUM` が
PostgreSQL のプロジェクトで誤りになることはありません。DB の違いは主に「チェックするかどうか」と
「パーサーが読めないと分かっている書き方を外すかどうか」に表れます。

## 動的 SQL の扱い

組み立ては、拡張機能の SQL プレビューと同じ規則です(`<where>` / `<set>` / `<trim>` の上書き、
`<include>` の展開と `<property>`、`<foreach>` の open / separator / close)。プレビュー
(`vscode-extension/src/sqlPreview.ts`)と解析エンジン(`analyzer/.../xml/DynamicSqlRenderer.java`)は
[`fixtures/sql-render/`](../fixtures/sql-render/) の同じ見本と期待値でテストしていて、片方だけ組み立て方を
変えるとテストが落ちます。

分岐は次の順に試し、**別の選び方でも同じ XML の位置で読めないとき**だけ報告します。

1. `<if>` をすべて成り立たせ、`<choose>` は最初の分岐、`<foreach>` は 2 回(プレビューの初期表示と同じ)
2. 誤りが `<if>` の外なら、`<if>` をすべて成り立たせず、`<choose>` は最後の分岐にしたもの
3. 誤りが `<if>` の中なら、その `<if>`(と、それを囲む `<if>`)だけを成り立たせたもの

同時には成り立たない 2 つの `<if>`(`ORDER BY` を 2 通り書き分けるなど)は、すべて成り立たせると
読めなくなりますが、片方だけにすると読めるので報告しません。分岐の組み立て方のミス(`AND` やカンマが
余るなど)は `dangling-sql-joiner` / `empty-dynamic-clause` が扱います。

## 読まないもの(フェイルオープン)

- `${...}` が残る statement(値しだいで SQL が変わる)。`<include>` の `<property>` で決まる `${...}` は置き換えて読む
- 見つからない `<include>` がある statement
- `statementType="CALLABLE"`、`BEGIN` / `DECLARE` で始まる PL/SQL ブロック、`CALL` / `{call ...}`
- Oracle の `RETURNING ... INTO`(JSqlParser が読めない)
- 末尾以外に `;` があり、複数の SQL をつないでいるもの(末尾の `;` 1 つは外して読む)
- パーサーが時間内に読み終わらなかったもの、字句の段階で読めなかったもの
- `.` でつないだ名前を `"A"."FINAL"` のように引用符で囲むと読めるもの。JSqlParser は `FINAL`、`OUTPUT`、
  `STATEMENT`、`SAMPLE`、`INT`、`MAP` など、Oracle では列名や別名に使える語の一部を予約語として扱い、
  `A.FINAL` や `INT.ID` の `.` で読めなくなるため。綴りの誤りやカンマ抜けは引用符で囲んでも直らないので、
  これまでどおり報告する

読めないことは SQL の誤りとは限らない(パーサーが知らない書き方かもしれない)ので、warning にしています。
誤検知があれば、`mybatis-lint-disable-next-line sql-syntax-error` で止められます。
