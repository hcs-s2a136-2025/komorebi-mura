package jp.komorebi.house;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jp.komorebi.village.Villages;
import jp.komorebi.world.SharedWorldService;

/**
 * 建てた家の中身をみんなで同じにする係。中身のルールは {@link HouseBook}、ここは保存と「だれが入れるか」の確認。
 * 家の持ち主と「だれでも入れる」設定は、共有の村の houses レイヤー（ownerId / open）を見る。
 */
@Service
public class HouseService {

    private static final Logger log = LoggerFactory.getLogger(HouseService.class);
    private static final TypeReference<Map<String, Long>> COUNTS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final SharedWorldService world;
    /** 村ごとの家の中身。データベースの鍵は、1番の村ならそのまま（"48,21"）、ほかの村なら "v2|48,21" */
    private final Map<Long, HouseBook> books = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Pattern VKEY = Pattern.compile("v(\\d{1,9})\\|(.+)");
    /** リセットで消えた家の箱の中身。持ち主（プレイヤーID）→ 品物 → 個数。次につないだときに本人へ返す */
    private final Map<Long, Map<String, Long>> returns = new LinkedHashMap<>();
    private static final String RET = "ret:";
    /** 最初からある家。オンラインでは「みんなの家」で、だれでも入れて、だれでも模様替えできる */
    public static final String HOME = "home";

    /** みんなの家の「鍵つきの箱」は、ひとりずつ別の箱になる */
    public static String personal(long playerId) {
        return "me:" + playerId;
    }

    public HouseService(JdbcTemplate jdbc, ObjectMapper om, SharedWorldService world) {
        this.jdbc = jdbc;
        this.om = om;
        this.world = world;
    }

    @PostConstruct
    void load() {
        jdbc.query("SELECT house_key, doc FROM house_room", rs -> {
            try {
                JsonNode d = om.readTree(rs.getString(2));
                if (rs.getString(1).startsWith(RET)) {
                    Map<String, Long> items = d.has("items") ? om.convertValue(d.get("items"), COUNTS) : null;
                    if (items != null) synchronized (returns) { returns.put(Long.parseLong(rs.getString(1).substring(RET.length())), items); }
                    return;
                }
                Map<String, Long> shared = d.has("storage") ? om.convertValue(d.get("storage"), COUNTS) : null;
                Map<String, Long> priv = d.has("priv") ? om.convertValue(d.get("priv"), COUNTS) : null;
                String k = rs.getString(1);
                long v = Villages.MAIN;
                java.util.regex.Matcher m = VKEY.matcher(k);
                if (m.matches()) { v = Long.parseLong(m.group(1)); k = m.group(2); }
                bookOf(v).restore(k, d.path("layout").isTextual() ? d.path("layout").asText() : null, shared, priv);
            } catch (Exception e) {
                log.warn("家の中身が読めなかった（{}）: {}", rs.getString(1), e.getMessage());
            }
        });
    }

    private HouseBook bookOf(long village) {
        return books.computeIfAbsent(village, v -> new HouseBook());
    }

    /** データベースに書くときの鍵（いまの村） */
    private static String dbKey(String key) {
        long v = Villages.current();
        return v == Villages.MAIN ? key : "v" + v + "|" + key;
    }

    /** いまの村の家の中身 */
    public HouseBook book() {
        return bookOf(Villages.current());
    }

    /** この家の持ち主か。持ち主が決まっていない古い家は、だれでも持ち主あつかい */
    public boolean isOwner(String key, long playerId) {
        if (HOME.equals(key)) return true;
        JsonNode h = world.layerValue("houses", key);
        if (h == null) return false;
        long owner = h.path("ownerId").asLong(-1);
        return owner < 0 || owner == playerId;
    }

    public boolean exists(String key) {
        if (HOME.equals(key)) return true;
        return world.layerValue("houses", key) != null;
    }

    /** 中に入れるか（持ち主か、持ち主が「だれでも入れる」にしているか、合鍵をもらっている） */
    public boolean canEnter(String key, long playerId) {
        if (HOME.equals(key)) return true;
        JsonNode h = world.layerValue("houses", key);
        if (h == null) return false;
        if (isOwner(key, playerId) || h.path("open").asBoolean(false)) return true;
        // 合鍵：持ち主が「この人は入っていい」とえらんだ人（keys: [{id, name}]）
        for (JsonNode k : h.path("keys")) if (k.path("id").asLong(-1) == playerId) return true;
        return false;
    }

    /** 模様替えしていいか（持ち主か、持ち主から「同居人」にえらばれた合鍵の人。みんなの家はだれでも） */
    public boolean canEdit(String key, long playerId) {
        if (HOME.equals(key)) return true;
        if (isOwner(key, playerId)) return true;
        JsonNode h = world.layerValue("houses", key);
        if (h == null) return false;
        for (JsonNode k : h.path("keys")) if (k.path("id").asLong(-1) == playerId && k.path("edit").asBoolean(false)) return true;
        return false;
    }

    public boolean isOpen(String key) {
        if (HOME.equals(key)) return true;
        JsonNode h = world.layerValue("houses", key);
        return h != null && h.path("open").asBoolean(false);
    }

    /** その人に見せる形。みんなの家では、鍵つきの箱はその人だけの箱を見せる */
    public ObjectNode view(String key, boolean owner, long playerId) {
        if (!HOME.equals(key)) return view(key, owner);
        ObjectNode v = view(HOME, false);
        if (owner) v.set("priv", om.valueToTree(book().view(personal(playerId), true).get("priv")));
        return v;
    }

    /** ひとりで遊んでいたときの家にあった物を、その人だけの箱へ移す。@return 入った分 */
    public Map<String, Long> importHome(long playerId, Map<String, Long> items) {
        Map<String, Long> ok = new LinkedHashMap<>();
        String key = personal(playerId);
        items.forEach((id, n) -> { if (n != null && n > 0 && book().put(key, true, id, Math.min(n, HouseBook.MAX_MOVE)) >= 0) ok.put(id, Math.min(n, HouseBook.MAX_MOVE)); });
        if (!ok.isEmpty()) save(key);
        return ok;
    }

    public ObjectNode view(String key, boolean owner) {
        ObjectNode v = om.valueToTree(book().view(key, owner));
        String layout = v.path("layout").asText(null);
        if (layout != null) {
            try { v.set("layout", om.readTree(layout)); } catch (Exception e) { v.putNull("layout"); }
        }
        return v;
    }

    /** 変わった家を保存する（行が無ければ作る） */
    public void save(String key) {
        HouseBook.Room r = book().copy(key);
        if (r == null) return;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("layout", r.layout());
        doc.put("storage", r.shared());
        doc.put("priv", r.priv());
        String json;
        try { json = om.writeValueAsString(doc); } catch (Exception e) { return; }
        Timestamp now = Timestamp.from(Instant.now());
        int up = jdbc.update("UPDATE house_room SET doc = ?, updated_at = ? WHERE house_key = ?", json, now, dbKey(key));
        if (up == 0) jdbc.update("INSERT INTO house_room (house_key, doc, updated_at) VALUES (?, ?, ?)", dbKey(key), json, now);
    }

    /**
     * 共有の村をリセットしたとき、家の中身も消す。共有の村のリセットより先に呼ぶこと（持ち主を houses レイヤーで調べるため）。
     * 箱（みんなの収納箱・鍵つきの箱）に入っていた物は、持ち主ごとにまとめて取っておき、次につないだときに返す。
     */
    public void resetAll() {
        Map<Long, Map<String, Long>> add = new LinkedHashMap<>();
        for (String key : book().keys()) {
            if (!HouseBook.builtKey(key)) continue;
            JsonNode h = world.layerValue("houses", key);
            long owner = h == null ? -1 : h.path("ownerId").asLong(-1);
            HouseBook.Room r = book().copy(key);
            if (owner <= 0 || r == null) continue;
            Map<String, Long> to = add.computeIfAbsent(owner, k -> new LinkedHashMap<>());
            r.shared().forEach((id, n) -> to.merge(id, n, Long::sum));
            r.priv().forEach((id, n) -> to.merge(id, n, Long::sum));
        }
        // みんなの家と、ひとりずつの箱は村のリセットでは消さない
        for (String key : book().keys()) if (HouseBook.builtKey(key)) book().remove(key);
        long v = Villages.current();
        if (v == Villages.MAIN) jdbc.update("DELETE FROM house_room WHERE house_key LIKE '%,%' AND house_key NOT LIKE 'v%|%'");
        else jdbc.update("DELETE FROM house_room WHERE house_key LIKE ?", "v" + v + "|%,%");
        synchronized (returns) {
            add.forEach((owner, items) -> {
                if (items.isEmpty()) return;
                Map<String, Long> to = returns.computeIfAbsent(owner, k -> new LinkedHashMap<>());
                items.forEach((id, n) -> to.merge(id, n, (a, b) -> Math.min(HouseBook.MAX_COUNT, a + b)));
                saveReturns(owner, to);
            });
        }
    }

    /** リセットで返す物があれば受け取る（受け取ったら消える）。無ければ空 */
    public Map<String, Long> takeReturns(long owner) {
        Map<String, Long> items;
        synchronized (returns) { items = returns.remove(owner); }
        if (items == null) return Map.of();
        jdbc.update("DELETE FROM house_room WHERE house_key = ?", RET + owner);
        return items;
    }

    private void saveReturns(long owner, Map<String, Long> items) {
        String json;
        try { json = om.writeValueAsString(Map.of("items", items)); } catch (Exception e) { return; }
        Timestamp now = Timestamp.from(Instant.now());
        int up = jdbc.update("UPDATE house_room SET doc = ?, updated_at = ? WHERE house_key = ?", json, now, RET + owner);
        if (up == 0) jdbc.update("INSERT INTO house_room (house_key, doc, updated_at) VALUES (?, ?, ?)", RET + owner, json, now);
    }
}
