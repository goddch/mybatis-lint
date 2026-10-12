# 未解決 `typeAlias` 検証(学習メモ)

このドキュメントは `mybatis-lint/unresolved-type-alias` を追加した変更を
読むためのガイドです。

## 実装を読む順番

1. [`JavaTypeIndex`](../analyzer/src/main/java/com/mybatislint/analyzer/javaindex/JavaTypeIndex.java)
   - ワークスペース内の Java クラス/インターフェースの simple name を集める索引です。
2. [`AnalyzerCli`](../analyzer/src/main/java/com/mybatislint/analyzer/cli/AnalyzerCli.java)
   - 各 XML を解析する前に `JavaTypeIndex` を一度だけ作り、全ファイルの解析に渡します。
3. [`MapperReferenceValidator`](../analyzer/src/main/java/com/mybatislint/analyzer/xml/MapperReferenceValidator.java)
   の `validateTypeAliases` / `reportIfUnresolvedTypeAlias`
   - `<resultMap type>` / `parameterType` / `resultType` と、resultMap 内の
     `<association javaType>` / `<collection javaType>` / `<collection ofType>` /
     `<discriminator javaType>` / inline `<case resultType>`
     を見て、未解決候補を判定します。
4. [`JavaTypeIndexTest`](../analyzer/src/test/java/com/mybatislint/analyzer/javaindex/JavaTypeIndexTest.java) /
   [`MapperTypeAliasValidationTest`](../analyzer/src/test/java/com/mybatislint/analyzer/xml/MapperTypeAliasValidationTest.java)
   - 正常系、異常系、フェイルオープン系を具体例で確認できます。
5. [`fixtures/type-alias-validation`](../fixtures/type-alias-validation/)
   - CLI や VS Code 拡張で手動確認するためのサンプルです。

## なぜ `typeAlias` を静的に「完全に」解決できないのか

MyBatis の `typeAlias` は、次のいずれか(または複数)によって実行時に初めて
文字列とクラスの対応が確定します。

- `mybatis-config.xml` の `<typeAliases>` に書かれた個別の `<typeAlias>` 登録
- `<typeAliases package="...">` によるパッケージ一括登録
- Spring Boot 連携での `mybatis.type-aliases-package` のような設定値
- コード上での `TypeAliasRegistry#registerAlias` の直接呼び出し
- 何も登録しなければ、クラスの simple name(パッケージなしの名前)がそのまま
  alias として使えるというデフォルト挙動

このアナライザーは mapper XML と Java ソースだけを読んでおり、上記の
設定ファイルや起動コードまでは(現時点では)解析対象にしていません。
つまり、**「この typeAlias 文字列は本当にどのクラスを指すか」を 100% 正しく
言い切ることは、今のアナライザーの入力だけでは原理的に不可能** です。

## だからこそ heuristic + フェイルオープンにした

上記の理由から、このルールは「未解決だと断定する」のではなく、
「未解決の可能性が高い候補を warning で知らせる」という設計にしています。
判定は次の順序で行います(`reportIfUnresolvedTypeAlias` 参照)。

1. 値が空なら何もしない。
2. 値にドット(`.`)が含まれる場合、`com.example.User` のような
   完全修飾クラス名とみなし、対象外にする。
   - 本当に FQCN かどうかは classpath を読まないと厳密には判定できないが、
     実務上ドット入りの typeAlias 値はほぼ FQCN であり、仮に存在しないクラス名
     だったとしても、それは「クラスが存在しない」という別種の問題であって
     typeAlias 解決の問題ではないため、このルールでは踏み込まない。
3. 値に `${` が含まれる場合、`<include refid>` の動的参照と同じ理由
   (実行時のプロパティ置換で値が変わり、静的には確定できない)で対象外にする。
4. 値が MyBatis 組み込み alias 一覧(`string`, `map`, `list`, `int` など、
   `TypeAliasRegistry` がデフォルトで登録する alias)に含まれる場合、
   ワークスペースに対応する Java クラスが無くても正当に解決できるため対象外にする。
5. 値の先頭が小文字の場合、上記の組み込み一覧に無くても対象外にする。
   個別に登録された小文字 alias である可能性を否定できないため、
   「誤検知を出すくらいなら報告しない」という本プロジェクトの一貫した方針に
   合わせている。
6. ここまで残った値は「大文字始まりの DTO/Entity らしい simple name」。
   `JavaTypeIndex` に同じ simple name(大文字小文字は無視)のクラス/インターフェースが
   見つかれば解決済みとし、見つからなければ
   `mybatis-lint/unresolved-type-alias` を warning として報告する。

## 誤検知(false positive)と見逃し(false negative)のトレードオフ

- **誤検知**: mybatis-config.xml 側で明示的に登録された大文字始まりの alias
  (例: `<typeAlias alias="UserAlias" type="com.example.dto.UserDto"/>`)は、
  ワークスペース内に `UserAlias` という simple name のクラスが実在しなくても
  正当な設定です。このケースはこのルールでは誤って warning が出ます。
  そのため severity を `error` ではなく `warning` にとどめ、利用者が
  「候補として一応知らせる」レベルの情報として扱えるようにしています。
- **見逃し**: 逆に、workspace 内にたまたま同じ simple name のクラスが
  存在してしまえば(パッケージが違うだけの同名クラスなど)、実際には
  タイプミスであっても解決済みとみなして見逃します。これは
  「誤検知を減らすために許容する」設計判断です。
- 総じて、このルールは「静的解析だけで完全な正解を出す」のではなく、
  「レビューで人が確認する価値のある候補を、ノイズを抑えて提示する」ことを
  目的にしています。

## 対象と段階的な拡張

`typeAlias` らしき値は他にも `<parameterMap>` や TypeHandler の指定など
さまざまな場所に出てきますが、最初は `<resultMap type>` / `parameterType` /
`resultType` の 3 箇所から始めました。次の段階として、トップレベル resultMap 内の
`<association javaType>` / `<collection javaType>` / `<collection ofType>` に
同じ判定を適用しています。深いネストも対象で、`resultMap` 参照を併記していても
明示された型は検証します。警告の位置は該当する属性値です。

例えば `<collection property="items" ofType="MissingItem"/>` は、
ワークスペース内に `MissingItem` と同じ simple name の型が無い場合に warning を
出します。空値・動的値・FQCN・組み込み alias・小文字始まりの custom alias は
従来どおりフェイルオープンです。警告だけでは CLI の終了コードは変わりません。

トップレベル resultMap の外にある要素や `<association ofType>` は対象外です。
discriminator の `javaType` と、直下の inline case の `resultType` も、
トップレベル resultMap 内(深いネストを含む)で同じ判定を適用します。
例えば `<case value="admin" resultType="MissingAdmin"/>` は、対応する
simple name の型が無ければ属性値の位置に warning を出します。
`resultMap` 属性がある case は参照先を使うため、その `resultType` は対象外です
(空・動的な `resultMap` 属性も含む)。discriminator 自身の `javaType` と
case の `resultMap` 参照検証は独立して行います。case の値や型の互換性、
discriminator mapping のプロパティは検証しません。
`resultMap` / `select` 属性がある association/collection や `resultMap` 属性がある
case の子孫は MyBatis が inline mapping として処理しないため、そこにある
discriminator/case の型も対象外です。

型の省略時の推論、collection 型と要素型の互換性、ネストしたプロパティの検証、
外部 classpath や設定ファイルの読み込みは行いません。明示的な外部 alias 登録による
誤検知など、上記の heuristic の制限もそのまま適用されます。
