# Oracle SQL パーサーの比較(下調べ)

Oracle SQL の構文チェックに使うパーサーを選ぶための下調べです。まだ解析エンジンには
組み込んでいません。組み込む前に、実際のプロジェクトでよく使われている書き方を確かめます
(下の「実プロジェクトで確かめること」)。

## 候補

| | JSqlParser | Druid |
|---|---|---|
| 試したバージョン | 5.4 | 1.2.28 |
| ライセンス | LGPL 2.1 / Apache 2.0 のデュアル | Apache 2.0 |
| jar の大きさ | 約 2.0 MB | 約 4.5 MB(コネクションプールなども含む) |
| 実行時に必要なライブラリ | なし | 宣言上はなし。ただし一部の SQL で commons-lang3 が必要だった(下記) |
| 必要な Java | 11 以上 | 8 以上 |

参考までに、今の analyzer の jar(JavaParser を含む)は約 1.6 MB です。

## 試した SQL

自作の SQL で、Oracle 独自の書き方 18 個と、誤りとして弾いてほしい SQL 5 個を試しました。
MyBatis の `#{...}` は `?` に置き換えた形にしています。

| 正しい SQL | JSqlParser | Druid |
|---|---|---|
| ヒント句 `/*+ INDEX(u USER_PK) */` | ○ | ○ |
| `(+)` による外部結合 | ○ | ○ |
| `START WITH ... CONNECT BY PRIOR` | ○ | ○ |
| `ROWNUM` を使ったページング | ○ | ○(commons-lang3 が必要) |
| `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` | ○ | ○ |
| `MERGE INTO ... USING ... WHEN MATCHED` | ○ | ○ |
| `DECODE` / `NVL` / `NVL2` / `TO_CHAR` | ○ | ○ |
| 分析関数 `ROW_NUMBER() OVER (...)`、`LISTAGG ... WITHIN GROUP` | ○ | ○ |
| `INSERT ALL INTO ... SELECT * FROM dual` | ○ | ○ |
| `q'[it's]'` | ○ | **×**(エラー。`q'[it''s]'` は `q AS '[it's]'` と誤って読んだ) |
| `seq_t.NEXTVAL` | ○ | ○ |
| `PIVOT` | ○ | ○ |
| `WITH ... AS (...)` | ○ | ○ |
| `INTERVAL '1' DAY`、`DATE '2024-01-01'` | ○ | ○ |
| `FOR UPDATE SKIP LOCKED` | ○ | ○ |
| 別名付きの `UPDATE` と `EXISTS` | ○ | ○ |
| `MINUS` | ○ | ○ |
| `LIKE '%' \|\| ? \|\| '%' ESCAPE '\'` | ○ | ○ |

| 誤った SQL(エラーになってほしい) | JSqlParser | Druid |
|---|---|---|
| `SELECT id, FROM t` | ○(エラー) | ○(エラー) |
| `SELECT id FORM t` | ○ | ○ |
| `SELECT * FROM t WHERE` | ○ | ○ |
| `UPDATE t SET a = ? b = ?`(カンマ抜け) | ○ | **×**(`b = ?` を黙って捨て、`UPDATE t SET a = ?` として読んだ) |
| `SELECT * FROM t WHERE 1 = 1 AND` | ○ | ○ |

## 今の時点の判断

**JSqlParser が有力です。**

- lint にとっていちばん困るのは「誤った SQL を正しいものとして読んでしまう」ことです。
  Druid はカンマ抜けを黙って読み飛ばし、`q'...'` を別の意味に読みました。JSqlParser は
  試したすべての誤りを弾き、正しい SQL はすべて読めました
- Druid は、依存として宣言していない commons-lang3 を一部の SQL で必要としました。
  同梱する jar を作るときに気づきにくい問題です
- JSqlParser はデュアルライセンスなので、このプロジェクトと同じ Apache 2.0 で使えます。
  jar も小さめです

ただし、試したのは自作の SQL 23 個だけです。実際のプロジェクトの書き方で結果が変わる
可能性があります。

## 組み込むときの考え方

MyBatis の mapper XML の SQL はそのままでは SQL として読めないので、組み込むときは
次のようにします。

- `#{...}` は `?` に置き換える
- `${...}` を含む statement は、値によって SQL が変わるので検証しない
- `<if>` / `<choose>` / `<foreach>` などを含む statement は、最初は検証しない
  (組み合わせを展開する仕組みは別の課題。[roadmap.md](roadmap.md) の Dynamic SQL branch expansion)
- `<include>` は、参照先の `<sql>` が見つかる場合だけ展開する
- パーサーが読めない書き方は「誤り」ではなく「判断できない」とみなす必要がある。最初は
  warning にとどめ、実際のプロジェクトで誤検知が出ないことを確かめてから扱いを決める

## 実プロジェクトで確かめること

会社のコードを持ち出さずに、次のことを確かめてもらえると判断の材料になります。

1. 上の表にない Oracle 独自の書き方がどれくらいあるか(例: `MODEL` 句、`RETURNING INTO`、
   `XMLTABLE`、DB リンク `@dblink`、PL/SQL ブロック `BEGIN ... END;`)
2. `${...}` を使っている statement の割合(多いと、検証できる statement が減る)
3. `<if>` などの動的要素を含まない statement の割合(最初はこれだけを検証する)
4. 1 つの statement に複数の SQL を `;` でつないでいるか
