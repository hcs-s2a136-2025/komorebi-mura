#!/usr/bin/env bash
# VM を引っ越すときの道具（たとえば 1GB の Micro → 6GB の Ampere）
#
#   前の VM で：  bash /opt/komorebi-mura/deploy/oracle/move-vm.sh save
#                 → アプリを止めて、~/komorebi-move.tgz（データベースと .env）を作る
#   新しい VM で：bash /opt/komorebi-mura/deploy/oracle/move-vm.sh load ~/komorebi-move.tgz
#                 → .env を写して（アドレスとメモリの設定はこの VM に合わせる）、データベースを入れて、動かす
#
# komorebi-move.tgz には .env（パスワード・招待コード）が入っているので、
# 自分のパソコンを通して運ぶだけにして、だれにも送らない。使いおわったら消す。
set -euo pipefail

DIR=/opt/komorebi-mura/deploy/oracle
cd "$DIR"
ENV=.env
envget() { grep -E "^$1=" "$ENV" 2>/dev/null | tail -1 | cut -d= -f2- || true; }
envset() {
  local k="$1" v="$2" tmp
  tmp=$(mktemp)
  grep -v -E "^$k=" "$ENV" > "$tmp" || true
  printf '%s=%s\n' "$k" "$v" >> "$tmp"
  cat "$tmp" > "$ENV"; rm -f "$tmp"
}
envdel() { local tmp; tmp=$(mktemp); grep -v -E "^$1=" "$ENV" > "$tmp" || true; cat "$tmp" > "$ENV"; rm -f "$tmp"; }
waitdb() { for i in $(seq 1 60); do docker compose exec -T db pg_isready -U komorebi -d komorebi >/dev/null 2>&1 && return 0; sleep 2; done; echo "!! データベースが起きない"; exit 1; }
TABLES="player player_save shared_world house_room board_post village flyway_schema_history"
counts() { local t; for t in $TABLES; do echo "  $t：$(docker compose exec -T db psql -tA -U komorebi -d komorebi -c "select count(*) from $t" 2>/dev/null || echo "?")"; done; }

save() {
  [ "$(envget DB_MOVED)" = "1" ] || { echo "!! この VM のデータベースはまだ VM の中にない（先に deploy.sh で引っ越しを）"; exit 1; }
  exec 9>/tmp/komorebi-deploy.lock; flock 9
  echo "---- アプリを止める（ここから先の遊んだ分は、新しい VM に入らないため） ----"
  docker compose stop app
  waitdb
  local tmp; tmp=$(mktemp -d)
  docker compose exec -T db pg_dump -U komorebi -d komorebi -Fc > "$tmp/komorebi.dump"
  cp "$ENV" "$tmp/env"
  counts | tee "$tmp/counts.txt"
  tar -czf "$HOME/komorebi-move.tgz" -C "$tmp" komorebi.dump env counts.txt
  chmod 600 "$HOME/komorebi-move.tgz"; rm -rf "$tmp"
  echo "できた：$HOME/komorebi-move.tgz（$(du -h "$HOME/komorebi-move.tgz" | cut -f1)）"
  echo "アプリは止めたまま。新しい VM で動いたのを確かめてから、この VM を止める"
  echo "（やっぱりやめるときは：cd $DIR && docker compose up -d）"
}

load() {
  local tgz="${1:-$HOME/komorebi-move.tgz}" tmp ip
  [ -f "$tgz" ] || { echo "!! $tgz がない（前の VM の save で作った物を、この VM のホームに置いてね）"; exit 1; }
  tmp=$(mktemp -d); tar -xzf "$tgz" -C "$tmp"
  [ -f "$tmp/komorebi.dump" ] && [ -f "$tmp/env" ] || { echo "!! 中身がちがう"; exit 1; }

  # .env を写す（アドレスとメモリの設定だけ、この VM に合わせる）
  install -m 600 "$tmp/env" "$ENV"
  ip=$(curl -fsS --max-time 10 https://api.ipify.org || true)
  if [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    envset SITE_ADDRESS "${ip//./-}.sslip.io"
  else
    echo "!! この VM の公開IPがわからなかった。.env の SITE_ADDRESS をあとで手で直してね"
  fi
  envdel JAVA_OPTS
  if [ "$(awk '/MemTotal/{print $2}' /proc/meminfo)" -lt 2500000 ]; then
    envset JAVA_OPTS '-Xmx320m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m'
  fi
  envset COMPOSE_PROFILES localdb
  [ -n "$(envget LOCAL_DB_PASSWORD)" ] && [ "$(envget DB_MOVED)" = "1" ] || { echo "!! .env に LOCAL_DB_PASSWORD か DB_MOVED=1 がない"; exit 1; }

  exec 9>/tmp/komorebi-deploy.lock; flock 9
  docker compose up -d db
  waitdb
  docker compose stop app 2>/dev/null || true
  docker compose exec -T db psql -q -U komorebi -d komorebi -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
  docker compose exec -T db pg_restore -U komorebi -d komorebi --no-owner --no-privileges < "$tmp/komorebi.dump" || echo "（pg_restore の注意は、下の数で確かめる）"
  echo "---- 前の VM ----"; cat "$tmp/counts.txt"
  echo "---- この VM ----"; counts | tee "$tmp/now.txt"
  if ! diff -q "$tmp/counts.txt" "$tmp/now.txt" >/dev/null; then
    echo "!! 数が合わない。アプリは動かさない（前の VM で docker compose up -d すれば、前のまま遊べる）"; exit 1
  fi
  mkdir -p "$HOME/komorebi-backups"; cp "$tmp/komorebi.dump" "$HOME/komorebi-backups/moved-$(date +%Y%m%d-%H%M).dump"
  rm -rf "$tmp"
  exec 9>&-
  echo "---- データは入った。アプリを作って動かす（はじめは10分ほど） ----"
  bash "$DIR/deploy.sh"
  echo "新しいアドレス：https://$(envget SITE_ADDRESS)"
}

case "${1:-}" in
  save) save ;;
  load) shift; load "${1:-}" ;;
  *) echo "使い方：bash $0 save（前の VM）／ bash $0 load [~/komorebi-move.tgz]（新しい VM）"; exit 1 ;;
esac
