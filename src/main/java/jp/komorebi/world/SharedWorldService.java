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
        "stakes", "tilled", "houses", "quarry", "meta");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9,:_\\-]{1,24}");
    private static final int MAX_VALUE = 600, MAX_KEYS = 6000, MAX_DOC = 990_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private ObjectNode layers;
    private volatile boolean dirty;

    public SharedWorldService(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @PostConstruct
    void load() {
        layers = om.createObjectNode();
        List<String> rows = jdbc.queryForList("SELECT doc FROM shared_world WHERE id = 1", String.class);
        if (!rows.isEmpty()) {
            try {
                JsonNode n = om.readTree(rows.get(0));
                if (n instanceof ObjectNode o) layers = o;
            } catch (Exception e) {
                log.warn("共有の村のデータが読めなかったので、空から始めます: {}", e.getMessage());
            }
        }
    }

    /** 編集をまとめて反映し、実際に反映できたものだけを返す（ほかの人に配る用） */
    public synchronized ArrayNode apply(JsonNode list) {
        ArrayNode out = om.createArrayNode();
        if (list == null || !list.isArray()) return out;
        int n = 0;
        for (JsonNode e : list) {
            if (++n > 300) break;
            String l = e.path("l").asText(""), k = e.path("k").asText("");
            if (!LAYERS.contains(l) || !KEY.matcher(k).matches()) continue;
            JsonNode v = e.get("v");
            JsonNode node = layers.get(l);
            ObjectNode layer = node instanceof ObjectNode o ? o : layers.putObject(l);
            if (v == null || v.isNull()) {
                if (layer.remove(k) == null) continue;
                v = NullNode.getInstance();
            } else {
                if (v.toString().length() > MAX_VALUE) continue;
                if (!layer.has(k) && layer.size() >= MAX_KEYS) continue;
                layer.set(k, v);
            }
            ObjectNode a = out.addObject();
            a.put("l", l).put("k", k);
            a.set("v", v);
            dirty = true;
        }
        return out;
    }

    /** サーバーだけが書く情報（村の時計など）。プレイヤーからの edits では書きかえられない */
    public synchronized void putSystem(String name, JsonNode value) {
        layers.set(name, value);
        dirty = true;
    }

    public synchronized JsonNode getSystem(String name) {
        return layers.get(name);
    }

    /** レイヤーの中の1つだけ（コピー）。無ければ null */
    public synchronized JsonNode layerValue(String layer, String key) {
        JsonNode l = layers.get(layer);
        JsonNode v = l == null ? null : l.get(key);
        return v == null || v.isNull() ? null : v.deepCopy();
    }

    public synchronized ObjectNode snapshot() {
        return layers.deepCopy();
    }

    public synchronized Map<String, Integer> stats() {
        Map<String, Integer> m = new LinkedHashMap<>();
        layers.fieldNames().forEachRemaining(f -> { if (!f.startsWith("_")) m.put(f, layers.get(f).size()); });
        return m;
    }

    /**
     * 共有の村を最初からにする。村の時計とジャックポットは残す。
     * 「村の代」（_epoch）を1つ進めるので、リセットのときにつないでいなかった人の端末に残っている古い村も、次につないだときに片付けられる。
     */
    public void reset() {
        synchronized (this) {
            JsonNode clock = layers.get("_clock"), jackpot = layers.get("_jackpot");
            long next = epoch() + 1;
            layers = om.createObjectNode();
            if (clock != null) layers.set("_clock", clock);
            if (jackpot != null) layers.set("_jackpot", jackpot);
            layers.put("_epoch", next);
            dirty = true;
        }
        persist();
    }

    /** 共有の村が何回リセットされたか（村の代）。0 から始まる */
    public synchronized long epoch() {
        return layers.path("_epoch").asLong(0);
    }

    /** プレイヤーに配る分だけ（サーバーだけが使う "_" で始まる情報は入れない） */
    public synchronized ObjectNode clientSnapshot() {
        ObjectNode out = om.createObjectNode();
        for (String l : LAYERS) {
            JsonNode n = layers.get(l);
            if (n instanceof ObjectNode o) out.set(l, o.deepCopy());
        }
        return out;
    }

    @Scheduled(fixedDelay = 10_000)
    public void persist() {
        String json;
        synchronized (this) {
            if (!dirty) return;
            json = layers.toString();
            dirty = false;
        }
        if (json.length() > MAX_DOC) {
            log.warn("共有の村のデータが大きすぎて保存できません（{}文字）。管理人室からリセットを検討してください", json.length());
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        int up = jdbc.update("UPDATE shared_world SET doc = ?, updated_at = ? WHERE id = 1", json, now);
        if (up == 0) jdbc.update("INSERT INTO shared_world (id, doc, updated_at) VALUES (1, ?, ?)", json, now);
    }

    @PreDestroy
    void flush() {
        persist();
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("world", clientSnapshot());
        welcome.put("worldEpoch", epoch());
    }
}
