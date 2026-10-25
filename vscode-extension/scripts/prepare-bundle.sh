#!/usr/bin/env bash
# 拡張機能に同梱する analyzer.jar と、最小構成の Java ランタイム(jre/)を用意する。
# jlink は実行した OS 向けのランタイムしか作らないため、CI では対象プラットフォームごとに
# そのプラットフォーム上でこのスクリプトを実行する。
#
# 前提: JAVA_HOME が JDK 17 を指していること、analyzer/build/libs/analyzer.jar がビルド済みであること。
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must point to a JDK 17 installation." >&2
  exit 1
fi

source_jar="../analyzer/build/libs/analyzer.jar"
if [ ! -f "$source_jar" ]; then
  echo "$source_jar not found. Run './gradlew jar' under analyzer/ first." >&2
  exit 1
fi

rm -rf jre analyzer.jar
cp "$source_jar" analyzer.jar

# モジュール一覧を固定で書くと、analyzer の依存が増えたときに実行時エラーになる。
# jdeps で jar から毎回算出して、必要なモジュールだけを含める。
modules="$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --multi-release 17 --print-module-deps analyzer.jar | tr -d '\r')"
echo "Bundling Java modules: $modules"

"$JAVA_HOME/bin/jlink" \
  --add-modules "$modules" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --output jre

# 同梱したランタイムで実際に解析できることを確認する。fixtures には誤りを含むものがあるので
# 終了コード 1(エラー診断あり)までは正常とみなす。
java_bin="jre/bin/java"
[ -f "$java_bin.exe" ] && java_bin="$java_bin.exe"
set +e
"$java_bin" -jar analyzer.jar ../fixtures > /dev/null
exit_code=$?
set -e
if [ "$exit_code" -ne 0 ] && [ "$exit_code" -ne 1 ]; then
  echo "Smoke test with the bundled runtime failed (exit code $exit_code)." >&2
  exit "$exit_code"
fi
echo "Bundled runtime OK."
