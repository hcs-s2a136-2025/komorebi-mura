#!/usr/bin/env bash
# サーバーの Java を「型が通るか」だけ確かめる（この作業環境では Maven Central に届かず mvn が使えないため）。
# 使い方: tools/javacheck/check.sh src/main/java/jp/komorebi/cash/*.java src/main/java/jp/komorebi/phone/*.java
# ・Spring や komorebi の一部のクラスは tools/javacheck/stubs の「形だけのクラス」で代わりをする。
#   足りないクラス・メソッドが出たら、本物と同じ形で stubs に足す（本物の中身は入れない）。
# ・stubs にあるクラスと同じものを引数に入れないこと（重複になる）。本番のビルドは Render の Docker ビルドで確かめる。
set -euo pipefail
cd "$(dirname "$0")/../.."
CP=$(ls /opt/gradle-*/lib/jackson-*.jar 2>/dev/null | tr '\n' ':')
OUT=$(mktemp -d)
javac -proc:none -Xlint:none -d "$OUT" -cp "$CP" $(find tools/javacheck/stubs -name '*.java') "$@"
echo "OK: 型が通りました（$# ファイル）"
rm -rf "$OUT"
