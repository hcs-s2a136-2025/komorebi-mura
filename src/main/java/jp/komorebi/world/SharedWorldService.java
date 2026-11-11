package jp.komorebi.world;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jp.komorebi.village.Villages;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * みんなで共有する村。地形・木・岩・柵・道・橋・家などを「レイヤー → 場所 → 値」の形で持つ。
 * メモリ上で変更を受け付けて、10秒ごとにまとめてDBへ保存する。
 */
@Service
public class SharedWorldService implements WelcomeContributor {

    private static final Logger log = LoggerFactory.getLogger(SharedWorldService.class);
    public static final Set<String> LAYERS = Set.of("terrain", "bridges", "trees", "saplings", "rocks", "fences", "paths",
        "stakes", "tilled", "houses", "quarry", "meta", "decor", "ccrops", "cfarm");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9,:_\\-]{1,24}");
    private static final int MAX_VALUE = 600, MAX_HOUSE = 2400, MAX_KEYS = 6000, MAX_DOC = 990_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    /** 村ごとの中身（村の番号 → レイヤー）。shared_world の行の id が村の番号 */
    private final Map<Long, ObjectNode> docs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Long> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SharedWorldService(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @PostConstruct
    void load() {
        docs.put(Villages.MAIN, read(Villages.MAIN));
    }

    private ObjectNode read(long village) {
        ObjectNode out = om.createObjectNode();
        List<String> rows = jdbc.queryForList("SELECT doc FROM shared_world WHERE id = ?", String.class, village);
        if (!rows.isEmpty()) {
            try {
                JsonNode n = om.readTree(rows.get(0));
                if (n instanceof ObjectNode o) out = o;
            } catch (Exception e) {
                log.warn("共有の村のデータ（村{}）が読めなかったので、空から始めます: {}", village, e.getMessage());
            }
        }
        return out;
    }

    /** いまの村の中身（はじめてなら読みこむ） */
    private ObjectNode layers() {
        return docs.computeIfAbsent(Villages.current(), this::read);
    }

    private void touch() {
        dirty.add(Villages.current());
    }

    /** 編集をまとめて反映し、実際に反映できたものだけを返す（ほかの人に配る用） */
    public synchronized ArrayNode apply(JsonNode list) {
        return apply(list, 0, "", null);
    }

    /**
     * 編集をまとめて反映する。飾り（decor）は、置いた人（o）だけが変えたり片付けたりできる。
     * 認められなかった変更は rejected に「いまの本当の値」として入れる（送ってきた人の画面を元に戻すため）。
     */
    public synchronized ArrayNode apply(JsonNode list, long playerId, String name, ArrayNode rejected) {
        ArrayNode out = om.createArrayNode();
        if (list == null || !list.isArray()) return out;
        int n = 0;
        for (JsonNode e : list) {
            if (++n > 300) break;
            String l = e.path("l").asText(""), k = e.path("k").asText("");
            if (!LAYERS.contains(l) || !KEY.matcher(k).matches()) continue;
            if ("meta".equals(l) && staleProj(k)) continue;   // 村を新しくする前の共同プロジェクトは、古い画面が送ってきても受けとらない
            JsonNode v = e.get("v");
            ObjectNode layers = layers();
            JsonNode node = layers.get(l);
            ObjectNode layer = node instanceof ObjectNode o ? o : layers.putObject(l);
            if ("decor".equals(l)) {
                JsonNode cur = layer.get(k);
                long owner = cur == null ? 0 : cur.path("o").asLong(0);
                boolean ok = cur == null || owner == 0 || owner == playerId;
                if (ok && v != null && !v.isNull()) {
                    String id = v.path("id").asText("");
                    if (!v.isObject() || !id.matches("[a-z0-9_]{1,32}")) ok = false;
                    else {
                        ObjectNode nv = om.createObjectNode().put("id", id).put("o", playerId).put("n", name == null ? "" : name);
                        // 電気の設備などを使わせる相手（s：プレイヤーIDの配列・8人まで・自分はふくめない）
                        if (v.path("s").isArray()) {
                            java.util.LinkedHashSet<Long> share = new java.util.LinkedHashSet<>();
                            for (JsonNode x : v.path("s")) { long sid = x.asLong(0); if (sid > 0 && sid != playerId && share.size() < 8) share.add(sid); }
                            if (!share.isEmpty()) { var arr = nv.putArray("s"); share.forEach(arr::add); }
                        }
                        v = nv; // 置いた人は、サーバーが知っている本人にする
                    }
                }
                if (!ok) {
                    if (rejected != null) { ObjectNode r = rejected.addObject().put("l", l).put("k", k); r.set("v", cur == null ? NullNode.getInstance() : cur.deepCopy()); }
                    continue;
                }
            }
            if ("cfarm".equals(l) && playerId > 0) {
                // 共同農園の区画は、つくった人（o）だけが変えたり消したりできる。大きさは 2〜12マス
                JsonNode cur = layer.get(k);
                long owner = cur == null ? 0 : cur.path("o").asLong(0);
                boolean ok = owner <= 0 || owner == playerId;
                if (ok && v != null && !v.isNull()) {
                    int x = v.path("x").asInt(-1), y = v.path("y").asInt(-1), w = v.path("w").asInt(0), h = v.path("h").asInt(0);
                    if (x < 0 || y < 0 || x > 250 || y > 250 || w < 2 || h < 2 || w > 12 || h > 12) ok = false;
                    else v = om.createObjectNode().put("x", x).put("y", y).put("w", w).put("h", h).put("o", owner > 0 ? owner : playerId).put("n", name == null ? "" : name);
                }
                if (!ok) {
                    if (rejected != null) { ObjectNode r = rejected.addObject().put("l", l).put("k", k); r.set("v", cur == null ? NullNode.getInstance() : cur.deepCopy()); }
                    continue;
                }
            }
            if ("houses".equals(l) && playerId > 0) {
                // 家は、持ち主（ownerId）だけが変えたり取りこわしたりできる。合鍵の人や、ほかの人の画面からは書きかえられない
                JsonNode cur = layer.get(k);
                long owner = cur == null ? 0 : cur.path("ownerId").asLong(0);
                boolean ok = owner <= 0 || owner == playerId;
                if (ok && v != null && !v.isNull()) {
                    if (!v.isObject()) ok = false;
                    else {
                        ObjectNode nv = ((ObjectNode) v).deepCopy();
                        nv.put("ownerId", owner > 0 ? owner : playerId); // 持ち主は、サーバーが知っている本人にする（ほかの人のものにはできない）
                        v = nv;
                    }
                }
                if (!ok) {
                    if (rejected != null) { ObjectNode r = rejected.addObject().put("l", l).put("k", k); r.set("v", cur == null ? NullNode.getInstance() : cur.deepCopy()); }
                    continue;
                }
            }
            if (v == null || v.isNull()) {
                if (layer.remove(k) == null) continue;
                v = NullNode.getInstance();
            } else {
                // 家は合鍵（20人まで）を持つので、ほかより長くてよい
                if (v.toString().length() > ("houses".equals(l) ? MAX_HOUSE : MAX_VALUE)) continue;
                if (!layer.has(k) && layer.size() >= MAX_KEYS) continue;
                layer.set(k, v);
            }
            ObjectNode a = out.addObject();
            a.put("l", l).put("k", k);
            a.set("v", v);
            touch();
        }
        return out;
    }

    /** サーバーだけが書く情報（村の時計など）。プレイヤーからの edits では書きかえられない */
    public synchronized void putSystem(String name, JsonNode value) {
        layers().set(name, value);
        touch();
    }

    public synchronized JsonNode getSystem(String name) {
        return layers().get(name);
    }

    /** レイヤーの中の1つだけ（コピー）。無ければ null */
    public synchronized JsonNode layerValue(String layer, String key) {
        JsonNode l = layers().get(layer);
        JsonNode v = l == null ? null : l.get(key);
        return v == null || v.isNull() ? null : v.deepCopy();
    }

    public synchronized ObjectNode snapshot() {
        return layers().deepCopy();
    }

    /** 保存の大きさ（管理人室の「村の一覧」に出す）：total＝いまの文字数、max＝保存できる上限、top＝大きい順の名前と文字数 */
    public record Usage(int total, int max, List<Map.Entry<String, Integer>> top) { }

    public Usage usageIn(long village) {
        return Villages.in(village, () -> {
            synchronized (this) {
                ObjectNode layers = layers();
                List<Map.Entry<String, Integer>> all = new java.util.ArrayList<>();
                layers.fieldNames().forEachRemaining(f -> all.add(Map.entry(f, layers.get(f).toString().length())));
                all.sort((x, y) -> y.getValue() - x.getValue());
                return new Usage(layers.toString().length(), MAX_DOC, List.copyOf(all.subList(0, Math.min(3, all.size()))));
            }
        });
    }

    public synchronized Map<String, Integer> stats() {
        Map<String, Integer> m = new LinkedHashMap<>();
        ObjectNode layers = layers();
        layers.fieldNames().forEachRemaining(f -> { if (!f.startsWith("_")) m.put(f, layers.get(f).size()); });
        return m;
    }

    /**
     * 村を新しくする（v0.78.0）。最初からになるのは、共同プロジェクト（meta の proj: / pjN:）・木・苗木・岩・採石場・みんなの水族館（_aqua）だけ。
     * 地形・橋・柵・道・畑・建てた家（と家の中身）・飾りと設備・共同農園、それに、サーバーだけが持つ帳簿（スマホコイン・闘鶏・大会・厩舎・リサイクル品・お手紙・討伐・新聞など）は残す。
     * 「村の世代」（_gen）を1つ進める。画面は世代がちがうと、自分のセーブの共同プロジェクトを最初にもどす（古い世代のプロジェクトを送り返さない）。
     * 木と岩は、サーバーが村の地図を知らないので、_regrow を立てておいて、次につないだ画面に「ふさがっていない場所へ生やしなおす」のを頼む。
     */
    public void renew() {
        synchronized (this) {
            ObjectNode layers = layers();
            for (String k : List.of("trees", "saplings", "rocks", "quarry", "_aqua")) layers.remove(k);
            if (layers.get("meta") instanceof ObjectNode meta) {
                List<String> del = new java.util.ArrayList<>();
                meta.fieldNames().forEachRemaining(k -> { if (projKey(k)) del.add(k); });
                del.forEach(meta::remove);
            }
            long next = gen() + 1;
            layers.put("_gen", next);
            layers.put("_regrow", next);
            touch();
        }
        persist();
    }

    private static final Pattern PJ = Pattern.compile("pj(\\d{1,6}):.*");
    static boolean projKey(String k) {
        return k.startsWith("proj:") || PJ.matcher(k).matches();
    }

    /** いまの世代のものではない共同プロジェクトの鍵か（世代0は proj:名前、世代Nは pjN:名前） */
    private boolean staleProj(String k) {
        long g = gen();
        if (k.startsWith("proj:")) return g > 0;
        var m = PJ.matcher(k);
        return m.matches() && Long.parseLong(m.group(1)) != g;
    }

    /** 村を新しくした回数（村の世代）。0 から始まる */
    public synchronized long gen() {
        return layers().path("_gen").asLong(0);
    }

    /** 木と岩を生やしなおすのを、まだだれもしていない世代（なければ 0） */
    public synchronized long regrowPending() {
        return layers().path("_regrow").asLong(0);
    }

    /** 画面が木と岩を生やしなおしたら呼ぶ */
    public synchronized void regrowDone(long g) {
        if (g > 0 && layers().path("_regrow").asLong(0) == g) { layers().remove("_regrow"); touch(); }
    }

    /** 共有の村が何回リセットされたか（村の代）。0 から始まる */
    public synchronized long epoch() {
        return layers().path("_epoch").asLong(0);
    }

    /** プレイヤーに配る分だけ（サーバーだけが使う "_" で始まる情報は入れない） */
    public synchronized ObjectNode clientSnapshot() {
        ObjectNode out = om.createObjectNode();
        for (String l : LAYERS) {
            JsonNode n = layers().get(l);
            if (n instanceof ObjectNode o) out.set(l, o.deepCopy());
        }
        return out;
    }

    @Scheduled(fixedDelay = 10_000)
    public void persist() {
        for (Long v : List.copyOf(dirty)) {
            String json;
            synchronized (this) {
                if (!dirty.remove(v)) continue;
                ObjectNode d = docs.get(v);
                if (d == null) continue;
                json = d.toString();
            }
            if (json.length() > MAX_DOC) {
                log.warn("共有の村のデータ（村{}）が大きすぎて保存できません（{}文字）。管理人室からリセットを検討してください", v, json.length());
                continue;
            }
            Timestamp now = Timestamp.from(Instant.now());
            int up = jdbc.update("UPDATE shared_world SET doc = ?, updated_at = ? WHERE id = ?", json, now, v);
            if (up == 0) jdbc.update("INSERT INTO shared_world (id, doc, updated_at) VALUES (?, ?, ?)", v, json, now);
        }
    }

    /** ほかの村のサーバー用の情報を読む（全部の村のランキングなど） */
    public synchronized JsonNode getSystemIn(long village, String name) {
        return Villages.in(village, () -> layers().get(name));
    }

    @PreDestroy
    void flush() {
        persist();
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("world", clientSnapshot());
        welcome.put("worldEpoch", epoch());
        welcome.put("worldGen", gen());
        if (regrowPending() > 0) welcome.put("regrow", regrowPending());
    }
}
