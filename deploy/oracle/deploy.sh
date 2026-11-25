#!/usr/bin/env bash
# main の最新を取ってきて、作りなおして動かす。GitHub Actions からも、手でも呼べる
# （このファイル自体が git で書きかわるので、全部読んでから動くように main にまとめてある。
#   書きかえた分が効くのは、その次の反映から）
set -euo pipefail

ENV=.env
envget() { grep -E "^$1=" "$ENV" 2>/dev/null | tail -1 | cut -d= -f2- || true; }
envset() {
  local k="$1" v="$2" tmp
  tmp=$(mktemp)
  grep -v -E "^$k=" "$ENV" > "$tmp" || true
  printf '%s=%s\n' "$k" "$v" >> "$tmp"
  cat "$tmp" > "$ENV"; rm -f "$tmp"
}

# ---- データベースを Neon から VM の中へ引っ越す（1回だけ・2026年11月20日） ----
# Neon の無料のコンピュート時間（月100）を、時計の保存などで24時間使い切ってしまったため。
# .env の DB_MOVED=1 があれば何もしない。失敗したら、アプリは今までどおり Neon につないだまま。
move_db() {
  [ "$(envget DB_MOVED)" = "1" ] && return 0
  local url user pass rest hostport host port dbq db
  url=$(envget DATABASE_URL); user=$(envget DATABASE_USER); pass=$(envget DATABASE_PASSWORD)
  case "$url" in jdbc:postgresql://*) ;; *) echo "DATABASE_URL が Neon の形ではないので、引っ越しはしない"; return 0 ;; esac
  rest=${url#jdbc:postgresql://}; hostport=${rest%%/*}; dbq=${rest#*/}; db=${dbq%%\?*}
  host=${hostport%%:*}; port=5432; [ "$hostport" != "$host" ] && port=${hostport##*:}
  host=${host/-pooler./.}   # pg_dump は「プール」を通さない入口で
  # URL の ?user=…&password=… にも対応
  case "$dbq" in *\?*) local q=${dbq#*\?} kv; IFS='&' read -ra kv <<< "$q"; for p in "${kv[@]}"; do case "$p" in user=*) [ -z "$user" ] && user=${p#user=};; password=*) [ -z "$pass" ] && pass=${p#password=};; esac; done;; esac
  echo "---- データベースの引っ越し：Neon（$host / $db）→ VM の中 ----"

  if [ -z "$(envget LOCAL_DB_PASSWORD)" ]; then envset LOCAL_DB_PASSWORD "$(openssl rand -hex 24)"; fi
  envset COMPOSE_PROFILES localdb
  docker compose up -d db
  for i in $(seq 1 60); do docker compose exec -T db pg_isready -U komorebi -d komorebi >/dev/null 2>&1 && break; sleep 2; done

  # 引っ越しのあいだは書きこまれないように、アプリを止める
  docker compose stop app || true
  local dump=/tmp/komorebi-neon.dump
  if ! docker compose run --rm --no-deps -T -e PGHOST="$host" -e PGPORT="$port" -e PGDATABASE="$db" -e PGUSER="$user" -e PGPASSWORD="$pass" -e PGSSLMODE=require \
        db pg_dump -Fc --no-owner --no-privileges > "$dump"; then
    echo "!! Neon から取り出せなかった（Neon が止まっている？ Launch に切りかえたか見てね）。今までどおり Neon につなぐ"
    envset COMPOSE_PROFILES ""
    docker compose up -d app
    return 1
  fi
  echo "取り出した：$(du -h "$dump" | cut -f1)"
  # 前に入れかけた分があれば消してから入れる
  docker compose exec -T db psql -q -U komorebi -d komorebi -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
  docker compose exec -T db pg_restore -U komorebi -d komorebi --no-owner --no-privileges < "$dump" || echo "（pg_restore の注意は、下の数で確かめる）"

  # 数をくらべる
  local ok=1 t a b
  for t in player player_save shared_world house_room board_post village flyway_schema_history; do
    a=$(docker compose run --rm --no-deps -T -e PGHOST="$host" -e PGPORT="$port" -e PGDATABASE="$db" -e PGUSER="$user" -e PGPASSWORD="$pass" -e PGSSLMODE=require db psql -tA -c "select count(*) from $t" 2>/dev/null || echo "?")
    b=$(docker compose exec -T db psql -tA -U komorebi -d komorebi -c "select count(*) from $t" 2>/dev/null || echo "?")
    echo "  $t：Neon $a ／ VM $b"
    [ "$a" = "$b" ] && [ "$a" != "?" ] || ok=0
  done
  if [ "$ok" != "1" ]; then
    echo "!! 数が合わないので、切りかえない。今までどおり Neon につなぐ"
    envset COMPOSE_PROFILES ""
    docker compose up -d app
    return 1
  fi

  # 切りかえ（Neon の値は NEON_… として残しておく）
  envset NEON_DATABASE_URL "$url"; envset NEON_DATABASE_USER "$user"; envset NEON_DATABASE_PASSWORD "$pass"
  envset DATABASE_URL "jdbc:postgresql://db:5432/komorebi"
  envset DATABASE_USER komorebi
  envset DATABASE_PASSWORD "$(envget LOCAL_DB_PASSWORD)"
  envset DB_MOVED 1
  mkdir -p "$HOME/komorebi-backups"; cp "$dump" "$HOME/komorebi-backups/neon-$(date +%Y%m%d-%H%M).dump"; rm -f "$dump"
  echo "引っ越しおわり：これからは VM の中のデータベースにつなぐ"
}

# VM の中のデータベースを、反映のたびに取っておく（7つまで）
backup_db() {
  [ "$(envget DB_MOVED)" = "1" ] || return 0
  mkdir -p "$HOME/komorebi-backups"
  local f="$HOME/komorebi-backups/komorebi-$(date +%Y%m%d-%H%M).dump"
  if docker compose exec -T db pg_dump -U komorebi -d komorebi -Fc > "$f" 2>/dev/null; then
    echo "取っておいた：$f（$(du -h "$f" | cut -f1)）"
    ls -1t "$HOME"/komorebi-backups/komorebi-*.dump 2>/dev/null | tail -n +8 | xargs -r rm -f
  else rm -f "$f"; echo "（取っておけなかった）"; fi
}

# 共有の村の中身を数える（2026/11/25・道や木が消えた件の調べ）。いまのデータベースと、取っておいたバックアップの両方
SW_SQL="select id, length(doc) as chars, updated_at, (select string_agg(k || '=' || case when jsonb_typeof(doc::jsonb->k)='object' then (select count(*) from jsonb_object_keys(doc::jsonb->k))::text else jsonb_typeof(doc::jsonb->k) end, ' ' order by k) from jsonb_object_keys(doc::jsonb) k where k in ('terrain','trees','saplings','rocks','paths','fences','houses','decor','bridges','meta','cfarm','_gen','_epoch')) as layers from shared_world order by id"
diag_world() {
  [ "$(envget DB_MOVED)" = "1" ] || return 0
  echo "---- 共有の村（いま） ----"
  docker compose exec -T db psql -tA -F ' | ' -U komorebi -d komorebi -c "$SW_SQL" 2>&1 | cut -c1-600 || true
  echo "---- 前のアプリのログの、共有の村の警告 ----"
  docker compose logs --no-color app 2>&1 | grep -E '共有の村|大きすぎ|読めなかった|renew|新しくする' | tail -20 || true
  echo "---- 取っておいたバックアップの共有の村 ----"
  docker compose exec -T db psql -q -U komorebi -d komorebi -c "DROP DATABASE IF EXISTS komorebi_bk" >/dev/null 2>&1 || true
  local f
  for f in $(ls -1t "$HOME"/komorebi-backups/*.dump 2>/dev/null | head -12); do
    docker compose exec -T db psql -q -U komorebi -d komorebi -c "DROP DATABASE IF EXISTS komorebi_bk" >/dev/null 2>&1
    docker compose exec -T db psql -q -U komorebi -d komorebi -c "CREATE DATABASE komorebi_bk" >/dev/null 2>&1 || continue
    docker compose exec -T db pg_restore -U komorebi -d komorebi_bk --no-owner --no-privileges -t shared_world < "$f" >/dev/null 2>&1 || true
    echo "  $(basename "$f")："
    docker compose exec -T db psql -tA -F ' | ' -U komorebi -d komorebi_bk -c "$SW_SQL" 2>&1 | cut -c1-600 | sed 's/^/    /' || true
  done
  docker compose exec -T db psql -q -U komorebi -d komorebi -c "DROP DATABASE IF EXISTS komorebi_bk" >/dev/null 2>&1 || true
}

main() {
  exec 9>/tmp/komorebi-deploy.lock
  flock 9
  cd /opt/komorebi-mura
  git fetch -q origin main
  git reset -q --hard origin/main
  cd deploy/oracle
  if ! grep -q '^SITE_ADDRESS=.\+' "$ENV" 2>/dev/null; then
    echo "deploy/oracle/.env がまだ書かれていないよ（手順書の6を見てね）"
    exit 1
  fi
  local moved=0
  move_db || moved=1
  diag_world || true
  backup_db || true
  docker compose up -d --build --remove-orphans
  docker image prune -f >/dev/null
  echo "反映した: $(git rev-parse --short HEAD)"
  # 起動のようすを見る（動いているか・さいごのログ）。アプリが立ち上がるまで少し待つ
  sleep 75
  echo "---- コンテナ ----"
  docker compose ps || true
  echo "---- メモリ ----"
  free -m || true
  echo "---- アプリのログ（さいごの120行） ----"
  docker compose logs --no-color --tail=120 app 2>&1 | grep -v -i -E 'password|secret|token=' || true
  [ "$moved" = "0" ] || { echo "!! データベースの引っ越しは、できなかった（上を見てね）"; exit 1; }
}

main "$@"
