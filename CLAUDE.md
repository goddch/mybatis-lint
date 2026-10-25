# CLAUDE.md

MyBatis の mapper XML を静的解析する lint ツール。Java 製の解析エンジン（CLI）と、
それを呼び出す VS Code 拡張で構成される。このプロジェクトはコードもドキュメントも
すべて AI が書いている。そのため、このファイルとテストが品質を支える前提で作業すること。

## 構成

- `analyzer/` — Java 17 / Gradle（単一モジュール）。解析エンジン + CLI
  - `discovery` ワークスペースから mapper XML / Java ファイルを探す
  - `javaindex` JavaParser で Mapper インターフェースとクラスを索引化する
  - `xml` SAX による基本チェック（`MapperXmlAnalyzer`）と、DOM ベースの各 validator
    （`MapperReferenceValidator`、`MapperParameterValidator`、
    `MapperParameterTypeValidator`、`MapperResultTypeValidator`）
  - `model` `Diagnostic` などのデータ型と、ルール ID の一覧（`RuleIds`）
  - `json` 自前の JSON writer。`cli` `AnalyzerCli` が全体をつなぐ
- `vscode-extension/` — TypeScript。`java -jar analyzer.jar <workspace>` を実行して
  結果を Problems パネルに出すだけの薄いクライアント。`vscode` を import するのは
  `src/extension.ts` だけにして、他のファイルは `node --test` で単体テストできるようにしている
- `fixtures/` — テストと手動確認で使うサンプル。`invalid` 系は **わざと誤りを入れてある**
  ので、正しい形に「直して」はいけない
- `docs/` — アーキテクチャ、ロードマップ、診断 JSON のスキーマ、ルールごとの仕様

CLI と拡張の間の取り決めは JSON 出力だけ（`docs/diagnostics-schema.md`）。
形式を変えるときは両側とスキーマ文書を同時に更新する。

## コマンド

```sh
# analyzer（Java 17。.sdkmanrc あり。Gradle wrapper 同梱）
cd analyzer
./gradlew test                              # JUnit 5
./gradlew jar                               # build/libs/analyzer.jar（依存込みの fat jar）
java -jar build/libs/analyzer.jar ../fixtures   # 誤りを含む fixture があるので終了コード 1 が正常

# VS Code 拡張（Node 20+）
cd vscode-extension
npm install
npm run compile && npm run lint && npm test

# 配布用パッケージ（Java ランタイム同梱。jar のビルド後に実行）
JAVA_HOME=/path/to/jdk-17 npm run bundle
npx vsce package --skip-license --target linux-x64
```

配布する拡張機能には jlink で作った最小構成の Java 17 を同梱しているので、利用者は
Java を用意しなくてよい。jlink は実行した OS 向けのランタイムしか作れないため、
プラットフォーム別の VSIX を `.github/workflows/package.yml` で OS ごとに作る。

CI（`.github/workflows/ci.yml`）は上記をすべて実行する。変更後は、関係する側の
テストを手元で通してから完了とすること。

## 基本方針

- **誤検知を出さないことを最優先する（fail-open）。** 静的に確実と言えないケース
  （`${...}` を含む値、解決できない型や曖昧な型、外部設定に依存するものなど）は
  診断を出さずにスキップする。迷ったら警告しない
- 確実に壊れているものだけ `ERROR`、それ以外は `WARNING`。終了コードは
  ERROR がある場合だけ 0 以外になる
- MyBatis の設定ファイルやコンパイル済みのクラスパスは読み込まない。ワークスペースの
  ソースだけで判定する
- 実行時の依存ライブラリは最小限にする（現在は JavaParser のみ）。追加するときは理由を説明する

## 新しいルールを追加するとき

1. `model/RuleIds.java` に ID を追加する（`mybatis-lint/kebab-case`）
2. 該当する validator に実装する
3. `fixtures/` に誤り例と正しい例を追加する
4. テストを書く。検出するケースだけでなく、**fail-open でスキップするケースも**テストする
5. `README.md` と `docs/roadmap.md` を更新する。必要なら `docs/` にルールの仕様を書く

## ドキュメント

- 実装していない機能を、動くかのように README やロードマップに書かない
- 実装や方針を変えたら、同じ変更の中で関係するドキュメントも直す

## コードの書き方

- コメントは日本語で書き、「何をしているか」より「なぜそうしているか」を書く
- 周りのコードの命名や書き方に合わせる
