# VS Code 拡張機能の手動確認

このドキュメントでは、[`vscode-extension/`](../vscode-extension/) にある
VS Code 拡張機能を、繰り返し同じ手順で確認できるようにまとめています。

## 目的

以下を確認します。

- Extension Development Host で拡張機能を起動できること
- analyzer jar を正常に呼び出せること
- Problems パネルに診断結果を表示できること
- 問題のないワークスペースを再チェックしたとき、古い診断結果が消えること

## 事前準備

- Java 17 が利用可能であること
- Node.js 20 以上
- VS Code

開始前に analyzer jar をビルドします。

```sh
cd analyzer
./gradlew jar
```

jar は次の場所に生成される想定です。

```text
analyzer/build/libs/analyzer.jar
```

## 最初に一度だけ行う設定

1. VS Code で [`vscode-extension/`](../vscode-extension/) を開きます。
2. 通常は、拡張機能が sibling の
   [`analyzer/build/libs/analyzer.jar`](../analyzer/build/libs/analyzer.jar)
   を自動検出します。
3. 自動検出されない場合は、設定画面を開き、`mybatisLint.analyzerJarPath` に
   [`analyzer/build/libs/analyzer.jar`](../analyzer/build/libs/analyzer.jar) の絶対パスを設定します。
4. 通常は `JAVA_HOME` または SDKMAN の current Java を自動検出します。
5. 自動検出されない場合は、`mybatisLint.javaPath` に Java 実行ファイルの絶対パスを設定します。

## スモークテスト A: 不正な fixture で診断が出ることを確認する

1. [`vscode-extension/`](../vscode-extension/) を開いた状態で `F5` を押し、
   Extension Development Host を起動します。
2. Extension Development Host 側で
   [`fixtures/invalid/`](../fixtures/invalid/) フォルダを開きます。
3. コマンドパレットを開き、**MyBatis Lint: Check Workspace** を実行します。

期待結果:

- 次の情報メッセージが表示されます。

  ```text
  MyBatis Lint: checked workspace, found 6 error(s) and 0 warning(s) across 4 file(s).
  ```

- Problems パネルに診断が **6 件** 表示されます。
- 診断対象のファイルは次の 4 つです。
  - [`ChooseMisuseMapper.xml`](../fixtures/invalid/ChooseMisuseMapper.xml)
  - [`DuplicateIdMapper.xml`](../fixtures/invalid/DuplicateIdMapper.xml)
  - [`MalformedMapper.xml`](../fixtures/invalid/MalformedMapper.xml)
  - [`MissingNamespaceMapper.xml`](../fixtures/invalid/MissingNamespaceMapper.xml)

想定される内訳:

- [`MalformedMapper.xml`](../fixtures/invalid/MalformedMapper.xml): 1 件
  (`mybatis-lint/malformed-xml`)
- [`MissingNamespaceMapper.xml`](../fixtures/invalid/MissingNamespaceMapper.xml): 1 件
  (`mybatis-lint/missing-namespace`)
- [`DuplicateIdMapper.xml`](../fixtures/invalid/DuplicateIdMapper.xml): 1 件
  (`mybatis-lint/duplicate-statement-id`)
- [`ChooseMisuseMapper.xml`](../fixtures/invalid/ChooseMisuseMapper.xml): 3 件
  (`mybatis-lint/duplicate-otherwise`,
  `mybatis-lint/otherwise-outside-choose`,
  `mybatis-lint/when-outside-choose`)

## スモークテスト B: 正常な fixture で診断が消えることを確認する

1. 同じ Extension Development Host ウィンドウで **File: Open Folder...** を使い、
   [`fixtures/valid/`](../fixtures/valid/) を開きます。
2. もう一度 **MyBatis Lint: Check Workspace** を実行します。

期待結果:

- 次の情報メッセージが表示されます。

  ```text
  MyBatis Lint: checked workspace, found 0 error(s) and 0 warning(s) across 0 file(s).
  ```

- Problems パネルが空になります。
- ひとつ前の `fixtures/invalid` 実行時の診断結果が残っていません。

## スモークテスト C: `<foreach collection>` の未定義パラメータ検証を確認する

`mybatis-lint/undefined-parameter` ルールは `#{...}` / `${...}` / `<if test>` /
`<when test>` に加えて `<foreach collection="...">` も検証します。
[`fixtures/parameter-validation/`](../fixtures/parameter-validation/) には
Java Mapper インターフェースと対応する Mapper XML のペアがあり、
CLI から直接確認できます。

事前に analyzer jar をビルドしておきます(未実施の場合)。

```sh
cd analyzer
./gradlew jar
```

CLI で確認します。

```sh
java -jar build/libs/analyzer.jar ../fixtures/parameter-validation
```

期待結果:

- [`InvalidMapper.xml`](../fixtures/parameter-validation/src/main/resources/com/example/InvalidMapper.xml)
  の `findByIds` ステートメントで
  `mybatis-lint/undefined-parameter` 診断が出ます。
  メッセージは
  `Undefined parameter 'missingIds' in mapper method 'findByIds'.` です。
  これは対応する
  [`InvalidMapper.java`](../fixtures/parameter-validation/src/main/java/com/example/InvalidMapper.java)
  の引数名が `ids` であるのに対し、XML 側の `<foreach collection="missingIds">`
  がタイプミスしている(存在しない識別子を参照している)ためです。
- [`ValidMapper.xml`](../fixtures/parameter-validation/src/main/resources/com/example/ValidMapper.xml)
  では `<foreach>` に関する診断は一切出ません。
  - `findByIds` ステートメント: `collection="ids"` が引数名 `ids` と
    そのまま一致する、単純な参照のケース。
  - `findByGroups` ステートメント: `collection="request.groups"` という
    プロパティパス(ルート識別子 `request` だけを検証)と、
    外側の `<foreach item="group">` の変数を内側の
    `<foreach collection="group">` から参照する、ネストした `<foreach>` の
    ケース。どちらも診断は出ません。

VS Code 拡張から確認したい場合は、
[`fixtures/parameter-validation/`](../fixtures/parameter-validation/) フォルダを
Extension Development Host で開き、**MyBatis Lint: Check Workspace** を
実行してください。Problems パネルに `InvalidMapper.xml` の `findByIds` の
位置に診断が表示されれば成功です。

## スモークテスト D: `<include refid>` と `resultMap`/`resultType` 警告を確認する

`mybatis-lint/unresolved-include` ルールは、`<sql id="...">` と
`<include refid="...">` を突き合わせ、静的に解決できない include を検出します。
同じ fixture で、`mybatis-lint/result-map-and-type` の warning も確認できます。

事前に analyzer jar をビルドしておきます(未実施の場合)。

```sh
cd analyzer
./gradlew jar
```

CLI で確認します。

```sh
java -jar build/libs/analyzer.jar ../fixtures/include-validation
```

期待結果:

- [`IncludeInvalidMapper.xml`](../fixtures/include-validation/src/main/resources/com/example/IncludeInvalidMapper.xml)
  の `<include refid="MissingColumns">` に
  `mybatis-lint/unresolved-include` の error が出ます。
  `MissingColumns` という `<sql id>` が同じ mapper 内にも、完全修飾名として解決できる
  他 mapper にも存在しないためです。
- 同じ `IncludeInvalidMapper.xml` の
  `<select id="findAmbiguousMapping" resultMap="UserResultMap" resultType="com.example.User">`
  に `mybatis-lint/result-map-and-type` の warning が出ます。
  `resultMap` と `resultType` はどちらも結果マッピング戦略を表すため、
  同時に指定すると意図が曖昧になります。
- [`IncludeValidMapper.xml`](../fixtures/include-validation/src/main/resources/com/example/IncludeValidMapper.xml)
  では include 診断は出ません。
  - `refid="ActiveWhere"` は同じ mapper ファイル内の `<sql id="ActiveWhere">`
    を参照する正常系です。
  - `refid="com.example.CommonFragments.BaseColumns"` は
    [`CommonFragments.xml`](../fixtures/include-validation/src/main/resources/com/example/CommonFragments.xml)
    にある SQL 断片を namespace 付きで参照する正常系です。
  - `refid="${tenantPrefix}.DynamicColumns"` は実行時に値が変わる動的 refid なので、
    静的に断定せずフェイルオープンします。

VS Code 拡張から確認したい場合は、
[`fixtures/include-validation/`](../fixtures/include-validation/) フォルダを
Extension Development Host で開き、**MyBatis Lint: Check Workspace** を
実行してください。Problems パネルに error 1 件と warning 1 件が表示されれば成功です。

## トラブルシューティング

次のメッセージが表示された場合:

```text
MyBatis Lint: analyzer jar not found at "..."
```

[`analyzer/`](../analyzer/) で `./gradlew jar` を実行して jar を再作成し、
`mybatisLint.analyzerJarPath` の設定値を確認してください。

次のメッセージが表示された場合:

```text
MyBatis Lint: failed to launch 'java' (...)
```

Java 17 をインストールするか、`JAVA_HOME` を設定するか、
`mybatisLint.javaPath` を明示的に設定してください。
