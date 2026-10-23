# こもれび村 オンライン試作（v0.29.1）

Spring Boot で動く、こもれび村のオンライン試作サーバーです。

- **なまえと合言葉でセーブ**：持ち物・お金・畑・着せ替えはサーバーに自動で保存。同じなまえと合言葉で入れば、別のスマホやPC、URLが変わっても続きから遊べて、家の持ち主もそのまま。設定タブの「データを消して最初から」を押したときだけ消えます
- **いっしょに遊ぶ**：招待コードで参加すると、友達の姿が村に見えて、スタンプであいさつできます
- **共有の村**：池・木・岩・柵・道・橋・家・畑の場所・採石場は、つないでいる全員で同じ村になります（持ち物・お金・作物の育ち具合は各自のセーブ）。共同プロジェクト（噴水・街灯・花壇・時計塔）も全員で同じ。人の家には鍵がかかります
- **村の時計**：日付・時刻・天気はサーバーが決めて、全員同じになります。だれかがベッドで眠るか24:00になると、全員が次の日の朝に（オンライン中は倒れません）。速さは `TIME_RATE` で変えられます
- **チャットと伝言板**：Cキー（スマホは「話」）で吹き出しチャット。掲示板の「伝言板」タブには、あとから来た人も読める書き込みを残せます
- **みんなのどんぐりBJ**：カジノのBJ卓で「みんなの卓に行く」を選ぶと、オンラインの友達と同じ卓でディーラーのクルミさんに挑めます（最大5人・順番に1人ずつ・持ち時間25秒）。カードを配るのはサーバーなので、ズルはできません
- **ジャックポットとお小遣い**：スロットのジャックポットは全員で1つ。オンラインの友達と向かい合ってⒶ（Zキー）でお小遣いを渡せます
- **バスでおでかけ**：村のバス停から、みなと町・都会・すすきの・秋葉原へ。同じ街にいる仲間どうしは、街の中でも姿が見えます
- **地図**：Mキー（スマホは「地図」ボタン）で村の全体図。自分・仲間・村の人の場所がわかります
- **管理人へのお便り**：ゲームの「お便り」ボタンから、不具合やほしい機能を送れます。管理人が返事すると、ゲームの中でお知らせが出ます
- **管理人室**：`/admin` でお便りを読んで返事したり、プレイヤーの合言葉を消したり（忘れた人用）、状態（未対応・対応中・対応済み）を変えたり、つないでいる人全員にお知らせを出せます。共有の村のリセット、最近のチャット、伝言板の書き込みの削除もここから

---

## 1. 自分のPCで動かす

必要なもの：**Java 17 以上**（Mavenは無くても大丈夫。`mvnw` が初回だけ自動で落としてきます）

```bash
# Mac / Linux
./mvnw spring-boot:run

# Windows（PowerShell / コマンドプロンプト）
mvnw.cmd spring-boot:run
```

VS Code なら、`KomorebiApplication.java` を開いて「Run」でもOKです。

起動したら：

| 開くところ | URL |
|---|---|
| ゲーム | http://localhost:8080/ |
| 管理人室 | http://localhost:8080/admin |

- 招待コード：最初は `komorebi`
- 管理人室のパスワード：設定していないときは、**起動ログに毎回ランダムなものが出ます**（`ADMIN_PASSWORD が設定されていないので…` の行）
- データは `./data/komorebi.mv.db` に保存されます（消すと最初から）

動作確認のテスト：

```bash
./mvnw test
```

### 設定（環境変数）

| 名前 | 意味 | 何も書かないと |
|---|---|---|
| `INVITE_CODE` | 友達に教える合言葉 | `komorebi` |
| `ADMIN_USER` / `ADMIN_PASSWORD` | 管理人室のログイン | `admin` / 起動のたびにランダム |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | データベース | H2（`./data` のファイル） |
| `TIME_RATE` | 現実の1秒で進むゲーム内の分数。1.0で1日が約18分、2.0で約9分 | `1.0` |
| `ALLOWED_ORIGINS` | WebSocketを受け付けるページ | `*` |
| `PORT` | ポート | `8080` |

---

## 2. 友達に試してもらう（無料・お試し用）

Cloudflare のクイックトンネルを使うと、自分のPCのサーバーをそのまま外から開けます。

```bash
# 入れ方の例：Windows → winget install --id Cloudflare.cloudflared ／ Mac → brew install cloudflared
cloudflared tunnel --url http://localhost:8080
```

表示された `https://xxxx.trycloudflare.com` を友達に送って、招待コードも教えればOK。

- PCをつけっぱなしにしている間だけ遊べます
- URLは起動するたびに変わります
- お試し用の仕組みなので、ずっと置いておく場所には向きません

---

## 3. ずっと置いておく（Render ＋ Neon・無料）

1. **GitHub** にこのフォルダをリポジトリとして push する
2. **Neon**（https://neon.com）でプロジェクトを作り、接続情報をメモ
   - `DATABASE_URL` は `jdbc:postgresql://<ホスト>/<DB名>?sslmode=require` の形に書きかえる
   - ユーザー名とパスワードは `DATABASE_USER` / `DATABASE_PASSWORD` に分けて入れる
3. **Render**（https://render.com）で「New → Blueprint」からリポジトリを選ぶ
   - `render.yaml` の設定で Web サービスが作られます
   - 環境変数に `DATABASE_URL` `DATABASE_USER` `DATABASE_PASSWORD` `ADMIN_PASSWORD` `INVITE_CODE` を入れる
4. 発行された `https://xxxx.onrender.com` を友達に送る

あとは **`git push` するたびに自動で公開し直されます**。

> 友達が「自分の村」を建てたいときは、[docs/自分の村を建てる.md](docs/自分の村を建てる.md) を見てもらってね（フォークして、同じ手順で建てる方法）。

知っておくこと：

- 無料プランは15分だれも来ないと眠ります。次に開いたとき起きるまで1分くらいかかります
- `DATABASE_URL` を入れ忘れると H2 に保存されて、公開し直すたびに消えます
- 無料枠の内容は変わることがあるので、ときどき公式ページを確かめてください

---

## 4. 機能の足し方

### WebSocketのメッセージを1種類ふやす

`ws/MessageHandler` を実装した `@Component` を1つ作るだけです。例：釣れた魚を自慢する `{"type":"brag","fish":"ヌシ"}`

```java
@Component
public class BragHandler implements MessageHandler {
    public String type() { return "brag"; }
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        String fish = msg.path("fish").asText("");
        if (fish.length() > 12) return;                       // 送られてきた値は必ずチェック
        hub.broadcast(hub.json().createObjectNode()
            .put("type", "brag").put("id", from.playerId()).put("fish", fish), from);
    }
}
```

ゲーム側は `game-src/game.js` の `handle8()` の switch に `case "brag":` を足します。

### テーブルや列をふやす

`src/main/resources/db/migration/` に次の番号の `V6__add_xxx.sql` を足すと、起動時に Flyway が自動で反映します。**すでにある V1〜V5 のファイルは書きかえない**のがルールです。

### ゲーム本体を直す

`game-src/` にゲームのソースが入っています。

```bash
python game-src/build.py      # → src/main/resources/static/index.html を作り直す
```

- ドット絵を足すときは `export.py` を直して `python game-src/export.py game-src` で `data.js` を作り直します（Pillow が必要）
- 公開したら `game.js` の `CHANGES19`（アップデート履歴）のいちばん上に新しい版を足して、`application.properties` の `komorebi.version` と `game.js` の `CLIENT_VERSION` を同じ値に上げると、つないでいる人に更新のお知らせが出ます

---

## 5. 中身の地図

```
src/main/java/jp/komorebi/
  config/    設定・ログイン（Spring Security）・WebSocketの登録
  player/    参加（招待コード・なまえ・合言葉）とトークン
  feedback/  お便り（スレッドとメッセージ）
  ws/        リアルタイム通信（hello / move / cfg / emote / edits / chat / ping）
  world/     共有の村（メモリで受けて10秒ごとにDBへ保存）
  save/      プレイヤーごとのセーブデータ
  house/     家の中（模様・家具）とみんなの収納箱・鍵つきの箱（中身の計算は Spring なしの HouseBook.java）
  casino/    みんなのジャックポット・お小遣い・みんなのBJ（ルールは Spring なしの BjGame.java）
  event/     管理人が開くイベント（ルールは Spring なしの EventRules、進行・記録・ごほうびは EventService、画面は /admin/events）
  casino/mj/ みんなの麻雀（正式ルール。ルールと進行は Spring なしの MjRules / MjGame / MjAi、つなぐ係は MjTableService）
  clock/     村の時計（日付・時刻・天気、眠ると朝）
  board/     伝言板
  web/       ゲーム用API（/api/**）と管理人室（/admin/**）
src/main/resources/
  db/migration/   テーブルの定義（Flyway）
  templates/admin 管理人室の画面（Thymeleaf）
  static/         ゲーム本体（index.html）
game-src/   ゲームのソース
tools/mock-server/  Javaなしでゲーム側を試せる、同じ約束の模擬サーバー（Node.js）
```

### API（ゲームから呼ぶもの）

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/version` | 版と、つないでいる人数 |
| POST | `/api/join` | `{inviteCode, name, pass}` → `{token, id, name, created}`。同じなまえと合言葉なら前のプレイヤーに戻る（前の端末のトークンは使えなくなる） |
| GET | `/api/session` | 自分の情報と、未読の返事の数 |
| POST | `/api/me/name` | 名前を変える（ほかの人が合言葉つきで使っている名前は不可） |
| POST | `/api/me/pass` | `{pass}` 合言葉を決める・変える |
| GET / PUT / DELETE | `/api/save` | 自分のセーブを読む / 保存する `{doc, rev}` / 消す |
| GET / POST | `/api/feedback` | 自分のお便り一覧 / 新しく送る `{category: BUG\|IDEA\|OTHER, title, body, context}` |
| POST | `/api/feedback/{id}/messages` | 返事を書く |
| POST | `/api/feedback/{id}/read` | 管理人の返事を読んだ |
| GET / POST | `/api/board` | 伝言板を読む / 書き込む `{body}` |
| POST | `/api/board/{id}/delete` | 自分の書き込みを消す |

### WebSocket（`/ws`）のメッセージ

| type | 向き | 内容 |
|---|---|---|
| `hello` → `welcome` | 送る → 受け取る | 本人確認。welcome には、ほかの人・共有の村（`world`）・最近のチャット（`chat`）が入る |
| `move` / `cfg` / `emote` | 両方 | 位置・見た目・スタンプ |
| `edits` | 両方 | 共有の村の変更 `{list:[{l: レイヤー, k: 場所, v: 値 or null}]}`。飾り（`decor`）は置いた人だけが変えられ、認められなかった分は `by:0` の edits で本人に元の値が返る |
| `chat` | 両方 | 吹き出しチャット（60文字まで・1秒に1回まで） |
| `jp` / `jpWin` | 送る → `jp` を受け取る | スロットの積み立て・当たり。`jp` は新しい額（当たったときは `winner` と `won` も） |
| `bj` → `bjState` / `bjCharge` / `bjSettle` / `bjNg` | 送る `{act: sit\|leave\|bet\|hit\|stand\|double\|split\|look, amount}` | みんなのBJ。`bjState` は卓のようす（全員へ）、`bjCharge` は賭け金を引く（マイナスなら戻す）、`bjSettle` は精算（本人へ） |
| `mj` → `mjLobby` / `mjTable` / `mjNg` / `mjPay` | 送る `{act: list\|create\|sit\|start\|leave\|look\|discard\|tsumo\|kan\|kyuushu\|ron\|pon\|minkan\|chi\|pass\|next, a, b}` | みんなの麻雀。`mjTable` は自分の手牌だけ入った卓のようす（本人へ）、`mjPay` は精算（留守のときは次の welcome の `mjPay`） |
| `ev` → `evMe` / `evNg`、受け取る `evState` / `evBoard` / `evReward` / `evEnd` | 送る `{act: look\|join\|fish\|collect\|claim}` | イベント（開く・終えるのは管理人室だけ）。welcome に開催中の `event` と、まだ受け取っていない `evRewards` が入る |
| `gift` → `giftOk` / `giftNg` | 送る `{to, amount, rid}` | お小遣い。相手には `gift` が届く |
| `house` → `house` / `houseImport` | 送る `{act: get\|put\|import, key, layout, seed, items}`。`key:"home"` はオンラインの「みんなの家」（だれでも入れて模様替えでき、鍵つきの箱はひとりずつ別。`import` でひとり用の家の物を自分だけの箱へ） | 建てた家の中。`get` で中のようす `{layout, storage, priv}`（`priv` は持ち主にだけ）。`put` は持ち主だけ、同じ家にいる人へ届く。入れないときは `locked`、まだ無いときは `missing` |
| `box` → `boxOk` / `boxNg` / `houseBox` | 送る `{act: put\|take, key, which: shared\|priv, id, n, rid}` | 箱の出し入れ。みんなの収納箱は入れる人ならだれでも、鍵つきの箱は持ち主だけ。中身が変わると `houseBox` がその家にいる人へ届く |
| `sleep` → `clock` | 送る → 受け取る | 眠ると全員が次の朝に。`clock` は日付・時刻・速さ・天気（30秒ごとと、朝になったときに届く） |
| `board` / `notice` / `feedbackReply` / `worldReset` | 受け取る | 伝言板の新着・お知らせ・お便りの返事・共有の村のリセット |

welcome に情報を足したいときは `ws/WelcomeContributor` を実装した `@Component` を作ります（例：`SharedWorldService`、`ChatHandler`）。

`/api/**` は、ヘッダー `X-Player-Token` に参加したときのトークンを入れて呼びます。

### 安全のためにしていること

- トークンはそのまま保存せず、SHA-256のハッシュだけ保存
- 招待コードの比較は、時間差で中身がばれない方法（`MessageDigest.isEqual`）
- お便りと参加に回数制限（連投よけ）
- WebSocketは、届いた値の形・長さ・範囲を `Sanitize` でチェック。自由入力のチャットは今はなし（決まったスタンプだけ）
- 管理人室は Spring Security のフォームログイン＋CSRF対策。画面への出力は Thymeleaf の `th:text` で自動エスケープ

## 6. いまの試作でまだできないこと

- 持ち物・お金はサーバーに保存されるけれど、中身の正しさはゲーム側を信じている（友達どうしで遊ぶ前提）
- 麻雀を友達と同じ卓で遊ぶこと（いまはCPUとだけ）
- 洞窟での共闘（敵の動きをサーバーで計算する部分）
- アイテムの交換・露店の共有・土地の区画ごとの権限
- 木が生え戻る・苗木が育つ・岩が湧くのは、オンライン中は現実の時間（15分・30分・20分ごと）でも進みます
