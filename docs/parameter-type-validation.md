# `parameterType` と Java 引数型の突き合わせ検証

`mybatis-lint/parameter-type-mismatch` の仕様と設計判断をまとめたものです。

## 実装を読む順番

1. [`JavaMapperIndex`](../analyzer/src/main/java/com/mybatislint/analyzer/javaindex/JavaMapperIndex.java)
   の `MapperMethodInfo`
   - `getParameterTypes()` / `getParameterCount()` / `isSingleParameterVarArgs()`
     がこのルールで使う情報。Java Mapper メソッドの「引数の宣言型を
     ソースそのままの文字列で」保持している。
2. [`MapperParameterTypeValidator`](../analyzer/src/main/java/com/mybatislint/analyzer/xml/MapperParameterTypeValidator.java)
   - このルールの本体。`compare` メソッドが判定ロジックの中心。
3. [`MapperXmlAnalyzer`](../analyzer/src/main/java/com/mybatislint/analyzer/xml/MapperXmlAnalyzer.java)
   - 既存の `MapperReferenceValidator` / `MapperParameterValidator` と並べて
     `MapperParameterTypeValidator` を呼び出している。
4. [`MapperParameterTypeValidatorTest`](../analyzer/src/test/java/com/mybatislint/analyzer/xml/MapperParameterTypeValidatorTest.java)
   - 正常系、異常系、フェイルオープン系を具体例で確認できる。
5. [`fixtures/parameter-type-validation`](../fixtures/parameter-type-validation/)
   - CLI や VS Code 拡張で手動確認するためのサンプル。

## このルールが検証していること

MyBatis の mapper XML では、statement の `parameterType` 属性で
「この SQL に渡されるパラメータオブジェクトの型」を宣言できる。実際には
省略されることも多いが、明示している場合、対応する Java Mapper
インターフェースのメソッド引数と型が一致していなければ、リファクタリング時の
書き換え漏れやコピペミスの兆候であることが多い。

このルールは、**単一引数のメソッドだけ** を対象に、mapper XML の
`parameterType` と Java 側の引数型を突き合わせ、明らかな不一致を warning
として報告する。

## スコープを単一引数に絞った理由

複数引数のメソッド(例: `find(String name, String status)`)は、通常
`@Param` を使うか、MyBatis が暗黙的に `ParamMap`(実質 `Map` 相当)を
組み立てて渡す。この場合、`parameterType` に書かれた値と「実際に渡される
オブジェクトの型」は 1 対 1 に対応しないため、静的にはどう判定しても
誤検知のリスクが高い。0 引数のメソッド(パラメータ不要)も同様に対応する
実引数が無い。まずは「単一引数なら実引数型が一意に決まる」という、
最も静的解析で確信を持ちやすいケースだけに絞ってスコープを小さく保っている。

## `mybatis-lint/unresolved-type-alias` との責務分担

このプロジェクトには既に typeAlias 文字列を検証する
`mybatis-lint/unresolved-type-alias` ルールがある。両者は
「`parameterType` を見る」という点で重なって見えるが、判定の材料も
できることも異なるため、責務を分けている。

| | `unresolved-type-alias` | `parameter-type-mismatch` |
| --- | --- | --- |
| 対象属性 | `<resultMap type>` / `parameterType` / `resultType` | `parameterType` のみ |
| 何を見るか | XML の値の「文字列」だけ(ワークスペース内の simple name 一覧と比較) | Java Mapper メソッドの実引数型と比較 |
| Java Mapper 側の解決 | 不要(`JavaTypeIndex` だけで完結) | 必須(`JavaMapperIndex` でメソッドを一意に解決できないと動かない) |
| 典型的に拾う不具合 | 存在しないクラス名を alias として書いてしまった | Java 側の引数型をリファクタリングしたのに XML 側を直し忘れた |

つまり `unresolved-type-alias` は「この文字列は解決できそうな alias 候補か」
という 1 変数の疑い、`parameter-type-mismatch` は「Java 側で実際に読み取れた
型と食い違っていないか」という 2 変数の突き合わせである。resultType や
`<resultMap type>` をこのルールの対象に含めていないのも、Java 側に
「戻り値の型」という一意に比較できる情報源が無い(戻り値の型はメソッドの
戻り値であって、mapper XML の resultType と単純比較できるとは限らない
— `List<User>` を返すメソッドの resultType は `User` であるのが普通、など)
ためで、対象を広げると誤検知のリスクが跳ね上がる。

## 判定ロジックの詳細(`compare` メソッド)

1. **Java 型が安全に比較できるか判定する(`JavaTypeShape.parse`)。**
   - 配列型(`byte[]` など)は対象外。
   - ジェネリクスは `<...>` より前の外側の型だけを見る
     (例: `List<User>` → `List`)。
   - `T` や `E` のような 1〜2 文字の大文字だけの識別子は、ジェネリクスの
     型変数である可能性が高いため対象外。JavaParser はシンボル解決を
     行わないため、名前の見た目だけで判断するしかない。
2. **XML 側がドット(`.`)を含む → 完全修飾クラス名とみなす。**
   末尾の simple name を Java 型の simple name と比較する。
3. **XML 側が MyBatis 組み込み alias → 対応表と比較する。**
   - `int`/`long`/`short`/`double`/`float`/`boolean`/`byte`/`char` と
     それぞれの boxed 型、`string`、`date`、`decimal`/`bigdecimal`、
     `biginteger` は「閉じた小さな集合」なので、**exact match** で
     一致/不一致を断定する。
   - `map`/`list`/`set`/`collection`/`iterator`/`iterable`/`cursor`/
     `resultset` はユーザー定義の実装クラスがあり得るため、
     「一致は積極的に認めるが、不一致は primitive/boxed/String など
     明らかに無関係な型のときだけ」という非対称なルールにしている
     (詳細は次の節)。
   - `object` alias は「何でも受け入れる」ため、常に一致扱い。
4. **それ以外(simple name 風の文字列)。**
   Java 型の simple name と直接比較する。ただし先頭が小文字の場合は
   `unresolved-type-alias` と同じ理由で個別登録 alias の可能性を
   否定できず、フェイルオープンする。

## primitive/boxed の扱いについての設計判断

MyBatis の `TypeAliasRegistry` を実際に見ると、`"long"` alias は
`Long.class`(boxed)に登録されており、`"_long"` alias が primitive の
`long.class` に対応する。つまり厳密には `parameterType="long"` は
boxed `Long` を指しているはずである。

しかし、このルールは **primitive と boxed の違いを検出することを目的に
していない**。実務上、`long id` のような primitive 引数を持つメソッドに
対して `parameterType="long"` と書くのは自然で一般的な書き方であり、
MyBatis も実行時に自動ボクシングで問題なく動作する。ここで primitive と
boxed を区別して warning を出すと、多くの正しいコードに対して無用な
warning を出してしまい、「誤検知を出すくらいなら報告しない」という
プロジェクト全体の方針に反する。そのため、`EXACT_ALIAS_JAVA_TYPES` の
各エントリは常に primitive とその boxed 型の両方を受理する集合にしている
(例: `"long"` → `{"long", "Long"}`)。

## Map/List 系 alias を非対称ルールにした理由

`map`/`list`/`set` のような alias は、Java 側で `HashMap`/`ArrayList`
のような JDK 標準実装だけでなく、ユーザーが独自に実装した Map/List
サブタイプ(例: `class UserIdBatch implements List<Long> { ... }`)を
渡すことも珍しくない。simple name だけを見る本アナライザーには、
そのようなクラスが本当に `List` を実装しているかどうかを判定する手段が
無い。

そこで、このルールは alias を `MAP`/`LIST`/`SET`/`COLLECTION`/
`ITERATOR`/`ITERABLE`/`CURSOR`/`RESULTSET` という「ファミリー」に分類した
上で、次のように非対称にしている。

- **一致は緩く認めるが、ファミリーは区別する**: simple name が
  `Map`/`List`/`Set`/`Collection` で終わる場合、alias と **同じファミリー**
  のときだけ一致とみなす(`parameterType="map"` は末尾が "Map" の型とだけ
  一致し、"List"/"Set" 系とは一致させない)。`collection` alias だけは
  `java.util.Collection` が List/Set 共通の親インターフェースであることを
  踏まえ、List 系・Set 系のどちらの命名にも一致させる。
  `Iterator`/`Iterable`/`Cursor`/`ResultSet` は完全一致のみを認める。
- **既知の別ファミリーとの取り違えは不一致として報告する**: simple name の
  末尾が `Map`/`List`/`Set` のいずれかだと判別できるのに、alias が期待する
  ファミリーと異なる場合(例: `parameterType="map"` に対して `ArrayList`
  を渡している)は、コレクション同士の明確な取り違えとして
  `mybatis-lint/parameter-type-mismatch` を報告する。
- **不一致は Java 側が primitive/boxed/String のような、コレクションでは
  あり得ないことが確実な型のときも報告する**。
- それ以外(命名規則に従わないワークスペース固有の DTO/カスタム
  コレクション実装など)は、ユーザー定義のコレクション実装かもしれないため
  断定を避けてフェイルオープンする。

この設計により、「本当に明らかな不一致(コレクション alias に対して
スカラー値を渡している、または別のコレクション種別を渡している)」だけを
拾い、命名規則に従わないカスタムコレクション実装を誤って警告してしまう
リスクを避けている。

## 誤検知(false positive)と見逃し(false negative)のトレードオフ

- **誤検知のリスクが残る箇所**: 完全修飾クラス名や simple name の比較は
  「同じ simple name を持つ別パッケージのクラス」を区別できない
  (`com.example.User` と `com.other.User` を同じものとして扱ってしまう)。
  実務上はほぼ発生しないケースだが、理論上は false positive/negative の
  両方があり得る。
- **見逃しの方を優先する箇所**: Map/List 系のカスタム実装、ジェネリクスの
  型変数、vararg 引数など、静的に確信を持てないケースはすべて
  フェイルオープンしている。これにより「本当に明らかな食い違い」だけが
  warning として残るよう設計している。
- 総じて、このルールも `unresolved-type-alias` と同様に「静的解析だけで
  完全な正解を出す」のではなく、「レビューで人が確認する価値のある候補を、
  ノイズを抑えて提示する」ことを目的にしている。
