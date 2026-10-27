# MyBatis Lint（プレビュー版）

> ⚠️ **プレビュー版です。** 開発の初期段階のため、誤検知や検出漏れなどのバグがあり得ます。
> 結果は参考情報として扱ってください。

MyBatis の mapper XML を静的解析し、実行しないと気づきにくい誤りを Problems パネルに表示します。

## 使い方

1. mapper XML を含むフォルダを VS Code で開きます
2. コマンドパレット（`Ctrl+Shift+P` / `Cmd+Shift+P`）で **MyBatis Lint: Check Workspace** を実行します
3. 結果が Problems パネルに表示されます

**Java のインストールは不要です。** 解析に必要な Java ランタイムを拡張機能に同梱しています。

## 主な検出内容

- XML の構文エラー、`namespace` の欠落、statement ID の重複
- `<when>` / `<otherwise>` の誤った使い方
- 参照先が見つからない `<include refid>`、`resultMap`、`extends`
- Java の Mapper メソッドに存在しないパラメータの参照（`#{missing}` など）
- `parameterType` / `resultType` と Java メソッドの型の不一致
- resultMap の `property` に対応するフィールドや setter がないもの

確実に誤りと判断できないケースは、誤検知を避けるため報告しません。
MyBatis の設定ファイルやコンパイル済みのクラスは読み込まず、ワークスペース内のソースだけで判定します。

## プライバシー

解析はすべて手元の PC 上で完結します。ネットワーク通信は行わず、ソースコードを外部に送信することもありません。

## 設定

権限がなく読めないフォルダ（Docker のボリュームなど）は、自動的に飛ばして解析を続けます。

| 設定 | 説明 |
|---|---|
| `mybatisLint.exclude` | 解析しないフォルダやファイル。ワークスペースからの相対パスで、`*` や `**` が使えます（例: `docker`、`modules/*/generated`）。`/` を含まない名前は、どの階層のフォルダ名・ファイル名にも一致します |
| `mybatisLint.javaPath` | 解析に使う Java のパス。空なら同梱の Java を使います |
| `mybatisLint.analyzerJarPath` | 解析エンジンの jar のパス。空なら同梱の jar を使います |

## 不具合の報告

[GitHub Issues](https://github.com/goddch/mybatis-lint/issues) へお願いします。

## ライセンス

[Apache License 2.0](https://github.com/goddch/mybatis-lint/blob/main/LICENSE)

同梱している Java ランタイム（OpenJDK）は、OpenJDK 自体のライセンス（GPLv2 + Classpath Exception）に従います。ライセンス文書は拡張機能内の `jre/legal/` に含まれています。
