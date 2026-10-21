#!/bin/sh
# Mavenが入っていなくても動かせるようにする、小さなラッパー（初回だけMavenをダウンロード）
set -e
MVN_VER=3.9.11
MVN_HOME="$HOME/.m2/wrapper/apache-maven-$MVN_VER"
if [ ! -x "$MVN_HOME/bin/mvn" ]; then
  echo "Downloading Maven $MVN_VER ..."
  mkdir -p "$HOME/.m2/wrapper"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVN_VER/apache-maven-$MVN_VER-bin.tar.gz"
  TMP="$HOME/.m2/wrapper/maven-$MVN_VER.tgz"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$TMP"; else wget -q "$URL" -O "$TMP"; fi
  tar -xzf "$TMP" -C "$HOME/.m2/wrapper"
  rm -f "$TMP"
fi
exec "$MVN_HOME/bin/mvn" "$@"
