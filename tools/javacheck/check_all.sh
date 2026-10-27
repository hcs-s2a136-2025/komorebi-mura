#!/usr/bin/env bash
# サーバーの Java を全部まとめて javac で確かめる（Spring などは make_libstubs.py の形だけのクラスで代わりをする）。
# 設定のクラス（SecurityConfig・WebSocketConfig・KomorebiApplication）は形が複雑なので除く。中身の動きは確かめられないので、本番は Render で確かめる。
set -euo pipefail
cd "$(dirname "$0")/../.."
LS=$(mktemp -d); OUT=$(mktemp -d)
python3 tools/javacheck/make_libstubs.py "$LS" >/dev/null
CP=$(ls /opt/gradle-*/lib/jackson-*.jar /opt/gradle-*/lib/slf4j-api-*.jar 2>/dev/null | tr '\n' ':')
javac -proc:none -Xlint:none -d "$OUT" -cp "$CP" $(find "$LS" -name '*.java') $(find src/main/java -name '*.java' | grep -v 'config/SecurityConfig\|config/WebSocketConfig\|KomorebiApplication')
echo "OK: サーバー全体の型が通りました"
rm -rf "$LS" "$OUT"
