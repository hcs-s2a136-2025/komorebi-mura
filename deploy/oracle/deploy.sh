#!/usr/bin/env bash
# main の最新を取ってきて、作りなおして動かす。GitHub Actions からも、手でも呼べる
# （このファイル自体が git で書きかわるので、全部読んでから動くように main にまとめてある）
set -euo pipefail

main() {
  exec 9>/tmp/komorebi-deploy.lock
  flock 9
  cd /opt/komorebi-mura
  git fetch -q origin main
  git reset -q --hard origin/main
  cd deploy/oracle
  if ! grep -q '^SITE_ADDRESS=.\+' .env 2>/dev/null; then
    echo "deploy/oracle/.env がまだ書かれていないよ（手順書の6を見てね）"
    exit 1
  fi
  docker compose up -d --build --remove-orphans
  docker image prune -f >/dev/null
  echo "反映した: $(git rev-parse --short HEAD)"
}

main "$@"
