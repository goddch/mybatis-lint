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
