package jp.komorebi.coin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.clock.ClockService;
import jp.komorebi.phone.GameRankService;
import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.world.SharedWorldService;

/**
 * スマホコイン（スマホのゲームの中だけで使うお金）。本物のお金はいっさい使わない。村のお金（G・現金）で買う。
 * コインの数・ガチャで何が出たか・1日の上限は、ぜんぶサーバーが決める（端末で書きかえても増えない）。人ごと・村ごと。人に渡す・売る手段はない。
 * 共有の村の "_coin" に {p: {プレイヤーID: {b 残り, d 日本時間の日付, buy きょう買った数, ga きょう回したガチャの回数, sp きょう使った数,
 *   w 週, ws 今週使った数, pity 天井までの回数, lg ログインのごほうびをもらった日, bg ビンゴの日, rk ランキングの週}}, t: {プレイヤーID: にわとり}} で持つ。
 * 1日の区切りは日本時間（ゲームの中の日ではない）。G で買う（100G で1コイン）・現金で買う（1,000G札1枚で12コイン）は、どちらも1日100コインまで。
 * G や現金を減らすのはゲーム側（OK が返ってから）。ガチャは1回10・10回90コイン、1日30回まで、50回で限定家具が必ず出る（天井）。
 */
@Service
public class CoinService {

    static final String KEY = "_coin";
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    public static final int BUY_DAY = 100, GACHA_DAY = 30, PITY = 50, MAX_BAL = 99_999;
    public static final int G_PER_COIN = 100, COIN_PER_BILL = 12, ONE = 10, TEN = 90;
    public static final int LOGIN = 5, BINGO = 3, RANK = 10;
    static final Pattern WHAT = Pattern.compile("[a-z0-9]{1,12}");
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}ー・ 　]{1,12}");
    /** ランキングのごほうびを調べるゲーム（スマホのゲーム） */
    static final List<String> RANK_GAMES = List.of("blk", "tsu", "mrg", "jmp", "tori");
    static final String[] SEASONS = { "春", "夏", "秋", "冬" };

    /** ガチャの中身：[アイテムID, 星（3＝限定・2＝レア・1＝ふつう）, 重み] */
    public record Prize(String id, int star, double w) { }

    static final List<Prize> COMMON = List.of(new Prize("f_plant", 1, 16), new Prize("f_lamp", 1, 16), new Prize("f_rug", 1, 16), new Prize("f_cushion", 1, 16), new Prize("f_photo", 1, 16));
    static final Map<String, List<Prize>> SEASON = Map.of(
        "春", List.of(new Prize("f_sakura98", 3, 1.5), new Prize("f_hina98", 3, 1.5), new Prize("f_tanpopo98", 2, 4.25), new Prize("f_harucush98", 2, 4.25), new Prize("f_chocho98", 2, 4.25), new Prize("pc98_sakura", 2, 4.25)),
        "夏", List.of(new Prize("f_kingyo98", 3, 1.5), new Prize("f_furin98", 3, 1.5), new Prize("f_suika98", 2, 4.25), new Prize("f_mugi98", 2, 4.25), new Prize("f_hanabi98", 2, 4.25), new Prize("pc98_ukiwa", 2, 4.25)),
        "秋", List.of(new Prize("f_tsukimi98", 3, 1.5), new Prize("f_momiji98", 3, 1.5), new Prize("f_donshelf98", 2, 4.25), new Prize("f_pumpkin98", 2, 4.25), new Prize("f_kuri98", 2, 4.25), new Prize("pc98_donguri", 2, 4.25)),
        "冬", List.of(new Prize("f_snowchan98", 3, 1.5), new Prize("f_xtree98", 3, 1.5), new Prize("f_yukidaruma98", 2, 4.25), new Prize("f_yukiusagi98", 2, 4.25), new Prize("f_mikan98", 2, 4.25), new Prize("pc98_tonakai", 2, 4.25)));

    private final SharedWorldService world;
    private final ClockService clock;
    private final GameRankService ranks;
    private final PlayerService players;
    private final ObjectMapper om;
    private final Random random = new Random();
    private final Map<Long, Long> last = new ConcurrentHashMap<>();

    public CoinService(SharedWorldService world, ClockService clock, GameRankService ranks, PlayerService players, ObjectMapper om) {
        this.world = world;
        this.clock = clock;
        this.ranks = ranks;
        this.players = players;
        this.om = om;
    }

    /** 同じ人から 200ms に1回まで */
    public boolean slow(long id) {
        long now = System.currentTimeMillis();
        Long prev = last.put(id, now);
        return prev != null && now - prev < 200;
    }

    public String season() {
        return SEASONS[((Math.max(1, clock.day()) - 1) / 7) % 4];
    }

    public List<Prize> pool() {
        List<Prize> out = new ArrayList<>(SEASON.get(season()));
        out.addAll(COMMON);
        return out;
    }

    static String today() { return LocalDate.now(JST).toString(); }

    static String week() {
        LocalDate d = LocalDate.now(JST);
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private ObjectNode data() {
        JsonNode cur = world.getSystem(KEY);
        return cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
    }

    /** その人の帳簿（日付がかわっていたら、きょうの分を0にもどす） */
    private ObjectNode me(ObjectNode all, long id) {
        ObjectNode p = all.path("p") instanceof ObjectNode o ? o : all.putObject("p");
        String k = String.valueOf(id);
        ObjectNode m = p.path(k) instanceof ObjectNode o ? o : p.putObject(k);
        String d = today(), w = week();
        if (!d.equals(m.path("d").asText(""))) m.put("d", d).put("buy", 0).put("ga", 0).put("sp", 0);
        if (!w.equals(m.path("w").asText(""))) m.put("w", w).put("ws", 0);
        return m;
    }

    private void spent(ObjectNode m, int n) {
        m.put("sp", m.path("sp").asInt(0) + n).put("ws", m.path("ws").asInt(0) + n);
    }

    /** いまのようす：{bal, today:{buy,gacha,spent}, week:{spent}, max:{...}, pity, claimed:{login,bingo,rank}, season, pool:[{id,star,p}]} */
    public synchronized ObjectNode state(long id) {
        ObjectNode all = data(), m = me(all, id);
        world.putSystem(KEY, all);
        return view(m);
    }

    private ObjectNode view(ObjectNode m) {
        ObjectNode o = om.createObjectNode().put("type", "coin").put("a", "state").put("bal", m.path("b").asInt(0));
        o.putObject("today").put("buy", m.path("buy").asInt(0)).put("gacha", m.path("ga").asInt(0)).put("spent", m.path("sp").asInt(0)).put("date", m.path("d").asText(""));
        o.putObject("week").put("spent", m.path("ws").asInt(0));
        o.putObject("max").put("buy", BUY_DAY).put("gacha", GACHA_DAY).put("pity", PITY);
        o.put("pity", m.path("pity").asInt(0));
        o.putObject("claimed").put("login", today().equals(m.path("lg").asText(""))).put("bingo", today().equals(m.path("bg").asText(""))).put("rank", week().equals(m.path("rk").asText("")));
        o.put("season", season());
        List<Prize> pool = pool();
        double tot = pool.stream().mapToDouble(Prize::w).sum();
        ArrayNode arr = o.putArray("pool");
        for (Prize p : pool) arr.addObject().put("id", p.id()).put("star", p.star()).put("p", Math.round(p.w() / tot * 10000) / 100.0);
        return o;
    }

    /** 買う。by が "g" なら n コイン（100G ずつ）、"cash" なら n 枚のお札（1枚12コイン）。null ならOK（ゲーム側でお金を減らす） */
    public synchronized String buy(long id, String by, int n) {
        int coins;
        if ("g".equals(by)) { if (n < 1 || n > BUY_DAY) return "買う数がおかしいよ"; coins = n; }
        else if ("cash".equals(by)) { if (n < 1 || n > 9) return "お札の枚数がおかしいよ"; coins = n * COIN_PER_BILL; }
        else return "買い方がわからない";
        ObjectNode all = data(), m = me(all, id);
        if (m.path("buy").asInt(0) + coins > BUY_DAY) return "きょう買えるのは、あと" + Math.max(0, BUY_DAY - m.path("buy").asInt(0)) + "コインまで（1日" + BUY_DAY + "コイン）";
        m.put("buy", m.path("buy").asInt(0) + coins).put("b", Math.min(MAX_BAL, m.path("b").asInt(0) + coins));
        world.putSystem(KEY, all);
        return null;
    }

    /** ガチャを n 回（1 か 10）。出た物の一覧を返す。うまくいかないときは理由を err に入れて null */
    public synchronized List<String> gacha(long id, int n, StringBuilder err) {
        if (n != 1 && n != 10) { err.append("回す数がおかしいよ"); return null; }
        int cost = n == 1 ? ONE : TEN;
        ObjectNode all = data(), m = me(all, id);
        if (m.path("ga").asInt(0) + n > GACHA_DAY) { err.append("きょう回せるのは、あと").append(Math.max(0, GACHA_DAY - m.path("ga").asInt(0))).append("回まで（1日").append(GACHA_DAY).append("回）"); return null; }
        if (m.path("b").asInt(0) < cost) { err.append("コインが足りないよ（").append(cost).append("コイン）"); return null; }
        List<Prize> pool = pool();
        double tot = pool.stream().mapToDouble(Prize::w).sum();
        List<Prize> limited = pool.stream().filter(p -> p.star() == 3).toList();
        int pity = m.path("pity").asInt(0);
        List<String> got = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Prize p;
            pity++;
            if (pity >= PITY && !limited.isEmpty()) p = limited.get(random.nextInt(limited.size()));
            else {
                double r = random.nextDouble() * tot; p = pool.get(pool.size() - 1);
                for (Prize x : pool) { r -= x.w(); if (r < 0) { p = x; break; } }
            }
            if (p.star() == 3) pity = 0;
            got.add(p.id());
        }
        m.put("pity", pity).put("ga", m.path("ga").asInt(0) + n).put("b", m.path("b").asInt(0) - cost);
        spent(m, cost);
        world.putSystem(KEY, all);
        return got;
    }

    /** コインを使う（着せ替えの服・仕立て屋の布・時短・お話の先読み・天気予報）。null ならOK */
    public synchronized String spend(long id, int n, String what) {
        if (n < 1 || n > 500) return "使う数がおかしいよ";
        if (what == null || !WHAT.matcher(what).matches()) return "使い道がわからない";
        ObjectNode all = data(), m = me(all, id);
        if (m.path("b").asInt(0) < n) return "コインが足りないよ（" + n + "コイン）";
        m.put("b", m.path("b").asInt(0) - n);
        spent(m, n);
        world.putSystem(KEY, all);
        return null;
    }

    /** 無料でもらう。login（1日1回5）・bingo（1日1回3）・rank（1週1回10・スマホのゲームの「この1週間」で3位以内のとき）。もらえた数（0＝もらえない） */
    public synchronized int earn(long id, String why) {
        ObjectNode all = data(), m = me(all, id);
        int add = 0;
        if ("login".equals(why) && !today().equals(m.path("lg").asText(""))) { m.put("lg", today()); add = LOGIN; }
        else if ("bingo".equals(why) && !today().equals(m.path("bg").asText(""))) { m.put("bg", today()); add = BINGO; }
        else if ("rank".equals(why) && !week().equals(m.path("rk").asText("")) && inTop(id)) { m.put("rk", week()); add = RANK; }
        if (add > 0) { m.put("b", Math.min(MAX_BAL, m.path("b").asInt(0) + add)); world.putSystem(KEY, all); }
        return add;
    }

    private boolean inTop(long id) {
        for (String g : RANK_GAMES) {
            int r = ranks.board(g, id).path("me").path("weekRank").asInt(0);
            if (r >= 1 && r <= 3) return true;
        }
        return false;
    }

    public List<String> forecast() {
        return clock.forecast();
    }

    // ---- こもれび闘鶏：友だちのにわとり（にわとりの名前と力だけ） ----

    /** 自分のにわとりを見せる：{name, sp 走り, jp ジャンプ, st 体力}（それぞれ0〜999） */
    public synchronized void putTori(long id, JsonNode t) {
        String name = t.path("name").asText("").trim();
        if (!NAME.matcher(name).matches()) name = "にわとり";
        ObjectNode all = data();
        ObjectNode tt = all.path("t") instanceof ObjectNode o ? o : all.putObject("t");
        ObjectNode e = tt.putObject(String.valueOf(id)).put("name", name);
        for (String k : List.of("sp", "jp", "st")) e.put(k, Math.max(0, Math.min(999, t.path(k).asInt(0))));
        world.putSystem(KEY, all);
    }

    /** この村のほかの人のにわとり（20羽まで） */
    public synchronized ArrayNode toriList(long me) {
        ArrayNode out = om.createArrayNode();
        JsonNode tt = data().path("t");
        if (!tt.isObject()) return out;
        Map<Long, String> names = new ConcurrentHashMap<>();
        for (Player p : players.listAll()) names.put(p.getId(), p.getName());
        for (Iterator<Map.Entry<String, JsonNode>> it = tt.fields(); it.hasNext() && out.size() < 20; ) {
            var f = it.next();
            long pid;
            try { pid = Long.parseLong(f.getKey()); } catch (NumberFormatException ex) { continue; }
            if (pid == me || !names.containsKey(pid)) continue;
            ObjectNode o = f.getValue().deepCopy();
            o.put("id", pid).put("owner", names.get(pid));
            out.add(o);
        }
        return out;
    }
}
