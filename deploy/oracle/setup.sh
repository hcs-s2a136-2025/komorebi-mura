#!/usr/bin/env bash
# Oracle Cloud の Ubuntu 24.04 に、こもれび村を動かす準備をする（何度流しても大丈夫）
#   curl -fsSL https://raw.githubusercontent.com/hcs-s2a136-2025/komorebi-mura/main/deploy/oracle/setup.sh | sudo bash
set -euo pipefail

REPO=https://github.com/hcs-s2a136-2025/komorebi-mura.git
DIR=/opt/komorebi-mura
U=${SUDO_USER:-ubuntu}
H=$(getent passwd "$U" | cut -d: -f6)

if [ "$(id -u)" != 0 ]; then echo "sudo をつけて動かしてね"; exit 1; fi

echo "== 1/6 道具を入れる"
export DEBIAN_FRONTEND=noninteractive
echo iptables-persistent iptables-persistent/autosave_v4 boolean true | debconf-set-selections
echo iptables-persistent iptables-persistent/autosave_v6 boolean true | debconf-set-selections
apt-get update -q
apt-get install -y -q docker.io docker-compose-v2 git unattended-upgrades iptables-persistent
systemctl enable --now docker
usermod -aG docker "$U"

echo "== 2/6 OS の更新を自動に（日本時間 4:30 に、要るときだけ再起動）"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52komorebi <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "19:30";
EOF

echo "== 3/6 80番と443番を開ける（Oracle の Ubuntu は、はじめから閉じている）"
for p in 80 443; do
  if ! iptables -C INPUT -p tcp --dport "$p" -m state --state NEW -j ACCEPT 2>/dev/null; then
    n=$(iptables -L INPUT --line-numbers -n | awk '$2=="REJECT"{print $1; exit}')
    if [ -n "$n" ]; then iptables -I INPUT "$n" -p tcp --dport "$p" -m state --state NEW -j ACCEPT
    else iptables -A INPUT -p tcp --dport "$p" -m state --state NEW -j ACCEPT; fi
  fi
done
iptables -C INPUT -p udp --dport 443 -j ACCEPT 2>/dev/null || {
  n=$(iptables -L INPUT --line-numbers -n | awk '$2=="REJECT"{print $1; exit}')
  if [ -n "$n" ]; then iptables -I INPUT "$n" -p udp --dport 443 -j ACCEPT; else iptables -A INPUT -p udp --dport 443 -j ACCEPT; fi
}
netfilter-persistent save >/dev/null

echo "== 4/6 スワップ（ビルドのときの念のため）"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== 5/6 村のプログラムを取ってくる"
if [ ! -d "$DIR/.git" ]; then git clone -q "$REPO" "$DIR"; fi
chown -R "$U":"$U" "$DIR"
if [ ! -f "$DIR/deploy/oracle/.env" ]; then
  install -m 600 -o "$U" -g "$U" "$DIR/deploy/oracle/.env.example" "$DIR/deploy/oracle/.env"
fi
# メモリが少ない VM（AMD の Micro・1GB）では、Java を小さく動かす
if [ "$(awk '/MemTotal/{print $2}' /proc/meminfo)" -lt 2500000 ] && ! grep -q '^JAVA_OPTS=' "$DIR/deploy/oracle/.env"; then
  echo 'JAVA_OPTS=-Xmx320m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m' >> "$DIR/deploy/oracle/.env"
fi

echo "== 6/6 GitHub から反映するための鍵（この鍵では deploy.sh しか動かせない）"
K="$H/.ssh/komorebi_deploy"
if [ ! -f "$K" ]; then
  sudo -u "$U" mkdir -p "$H/.ssh"
  sudo -u "$U" ssh-keygen -q -t ed25519 -N "" -C komorebi-deploy -f "$K"
  echo "command=\"bash $DIR/deploy/oracle/deploy.sh\",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty $(cat "$K.pub")" >> "$H/.ssh/authorized_keys"
  chown "$U":"$U" "$H/.ssh/authorized_keys"
  chmod 600 "$H/.ssh/authorized_keys"
fi

echo
echo "準備できたよ。つぎは手順書の6（.env を書く）から。"
echo "  nano $DIR/deploy/oracle/.env"
