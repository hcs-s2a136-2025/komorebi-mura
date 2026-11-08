# Oracle Cloud への引っ越し

こもれび村のサーバーを、Render から Oracle Cloud の無料の VM（Always Free）に移す手順です。
データベースは今の Neon をそのまま使うので、村のデータの引っ越しはありません。

- できあがり：`main` に push すると、GitHub Actions が VM で `deploy/oracle/deploy.sh` を動かして反映する（今の Render と同じ）
- OS の更新は自動（日本時間 4:30 に、必要なときだけ再起動）。HTTPS の証明書も Caddy が自動で取って更新する
- 転送量の無料枠は月 10TB（Render は 5GB）

> 秘密（DB の接続情報・管理人のパスワード・招待コード）は、VM の `deploy/oracle/.env` にだけ書きます。
> GitHub にも、このリポジトリにも、だれかへのメッセージにも書かないでください。

---

## 0. 用意するもの

- クレジットカード（Oracle の本人確認に使うだけ。Always Free のままなら請求はありません）
- Render の画面にある環境変数の値（あとで `.env` に写す）
  - Render のサービス → 左の **Environment** に並んでいる `DATABASE_URL` `DATABASE_USER` `DATABASE_PASSWORD` `ADMIN_PASSWORD` `INVITE_CODE`
  - サービスが止まっていても、この画面は見られます

## 1. Oracle Cloud のアカウントを作る

1. https://www.oracle.com/cloud/free/ → **Start for free**
2. **Home Region** は **Japan East (Tokyo)**（あとから変えられません）
3. カードの確認まで進めて、アカウントができるのを待つ（数分〜しばらく）

**「Upgrade to Pay As You Go」は押さない**でください。押すと、無料の枠を超えたときに請求が来るようになります。

## 2. VM を作る

コンソールの左上のメニュー → **Compute** → **Instances** → **Create instance**

| 項目 | えらぶもの |
|---|---|
| Name | `komorebi` |
| Image | **Canonical Ubuntu 24.04**（「Change image」から） |
| Shape | 「Change shape」→ **Ampere** → **VM.Standard.A1.Flex**、**OCPU 1・メモリ 6GB** |
| Networking | そのまま（新しい VCN と公開サブネット。**Assign a public IPv4 address** にチェック） |
| Add SSH keys | **Generate a key pair for me** → **Save private key** を押して `.key` を保存（なくさない） |

**Create** を押して、状態が **Running** になったら、画面の **Public IP address** をメモします（例：`140.238.1.2`）。

- 「Out of capacity」と出たら、東京の無料の VM がいっぱいです。時間をおいてもう一度か、**Availability domain** を変えてためしてください
- OCPU 1・6GB にするのは、無料の枠に収めるためと、「使っていない VM」として止められないようにメモリを2割より多く使うためです（`docker-compose.yml` の `JAVA_OPTS`）

## 3. 80番と443番を開ける（Oracle の画面）

1. VM の画面 → **Primary VNIC** の **Subnet** のリンク → **Security** → **Default Security List**
2. **Add Ingress Rules** を2回：
   - Source CIDR `0.0.0.0/0`・IP Protocol **TCP**・Destination Port Range `80`
   - Source CIDR `0.0.0.0/0`・IP Protocol **TCP**・Destination Port Range `443`

（VM の中の壁は、次の `setup.sh` が開けます）

## 4. VM に入る（SSH）

**Windows（PowerShell）**

```powershell
mkdir $HOME\.ssh -Force
move $HOME\Downloads\ssh-key-*.key $HOME\.ssh\oracle.key
icacls $HOME\.ssh\oracle.key /inheritance:r /grant:r "$($env:USERNAME):R"
ssh -i $HOME\.ssh\oracle.key ubuntu@140.238.1.2
```

**Mac**

```bash
mv ~/Downloads/ssh-key-*.key ~/.ssh/oracle.key && chmod 600 ~/.ssh/oracle.key
ssh -i ~/.ssh/oracle.key ubuntu@140.238.1.2
```

`140.238.1.2` は、自分の VM の公開IPに変えてください。はじめて入るときに `yes/no` と聞かれたら `yes`。

## 5. 準備のスクリプトを流す（VM の中で）

```bash
curl -fsSL https://raw.githubusercontent.com/hcs-s2a136-2025/komorebi-mura/main/deploy/oracle/setup.sh | sudo bash
```

Docker・OS の自動更新・壁の穴あけ・村のプログラム（`/opt/komorebi-mura`）・反映用の鍵を用意します。何度流しても大丈夫です。
終わったら、いちど `exit` して、もう一度 4 の `ssh` で入りなおしてください（Docker を使えるようにするため）。

## 6. `.env` を書く（VM の中で）

```bash
nano /opt/komorebi-mura/deploy/oracle/.env
```

- `SITE_ADDRESS=` には、公開IPの `.` を `-` に変えて、うしろに `.sslip.io` をつけたものを書く
  （例：IP が `140.238.1.2` なら `SITE_ADDRESS=140-238-1-2.sslip.io`）
- ほかの行には、0 でメモした Render の値を、`=` のあとに空白なしで書く
- 保存は `Ctrl+O` → `Enter`、閉じるのは `Ctrl+X`

## 7. 動かす（VM の中で）

**先に Render を止めておきます**（同じデータベースを2つのサーバーで使うと、村の状態がぶつかります）。
Render のサービス → **Settings** → いちばん下の **Suspend Web Service**（いま止まっているなら、そのままで大丈夫。あとで動き出さないように、こちらでも止めておく）。

```bash
bash /opt/komorebi-mura/deploy/oracle/deploy.sh
```

はじめはビルドに10分ほどかかります。`反映した: xxxxxxx` と出たら、ブラウザで `https://140-238-1-2.sslip.io` を開いてください（自分のアドレスで）。
村が開いて、ログインでき、管理人室（`/admin`）に入れたら成功です。

うまくいかないときのようすは、これで見られます：

```bash
cd /opt/komorebi-mura/deploy/oracle && docker compose logs --tail 100
```

## 8. push で自動で反映されるようにする

VM の中で、反映用の鍵を表示します：

```bash
cat ~/.ssh/komorebi_deploy
```

`-----BEGIN OPENSSH PRIVATE KEY-----` から `-----END OPENSSH PRIVATE KEY-----` まで、まるごとコピーします。
（この鍵では `deploy.sh` を動かすことしかできないようにしてあります）

GitHub のリポジトリ → **Settings** → **Secrets and variables** → **Actions** → **New repository secret** を2つ：

| Name | Secret |
|---|---|
| `ORACLE_HOST` | VM の公開IP（例：`140.238.1.2`） |
| `ORACLE_SSH_KEY` | いまコピーした鍵 |

これで、`main` に push するたびに **Actions** タブの「Oracle へ反映」が動きます。手で動かすときは、そこから **Run workflow**。

## 9. みんなに新しいアドレスを伝える

`https://○○○.onrender.com` は使えなくなるので、新しいアドレス（`https://140-238-1-2.sslip.io`）を友だちに送ってください。
セーブはサーバー（Neon）にあるので、新しいアドレスでログインすれば、そのまま続きから遊べます。

---

## ふだんのこと

- **更新**：今までどおり `main` に push するだけ
- **OS の更新**：自動。たまに日本時間 4:30 ごろ、1〜2分止まることがあります
- **VM が止められないか**：Oracle は「CPU・ネットワーク・メモリのどれも2割より少ない VM」を止めることがあります。この設定ではメモリを2割より多く使うので、ふつうは大丈夫です。もし止められたら、コンソールの Instances から **Start** で動きます
- **転送量**：コンソールの **Billing & Cost Management** → **Cost Analysis**。月 10TB までは無料
- **Render に戻すとき**：Oracle 側で `cd /opt/komorebi-mura/deploy/oracle && docker compose down`、Render で **Resume**
