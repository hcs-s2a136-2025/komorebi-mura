package jp.komorebi.house;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
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
    private final HouseBook book = new HouseBook();

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
                Map<String, Long> shared = d.has("storage") ? om.convertValue(d.get("storage"), COUNTS) : null;
                Map<String, Long> priv = d.has("priv") ? om.convertValue(d.get("priv"), COUNTS) : null;
                book.restore(rs.getString(1), d.path("layout").isTextual() ? d.path("layout").asText() : null, shared, priv);
            } catch (Exception e) {
                log.warn("家の中身が読めなかった（{}）: {}", rs.getString(1), e.getMessage());
            }
        });
    }

    public HouseBook book() {
        return book;
    }

    /** この家の持ち主か。持ち主が決まっていない古い家は、だれでも持ち主あつかい */
    public boolean isOwner(String key, long playerId) {
        JsonNode h = world.layerValue("houses", key);
        if (h == null) return false;
        long owner = h.path("ownerId").asLong(-1);
        return owner < 0 || owner == playerId;
    }

    public boolean exists(String key) {
        return world.layerValue("houses", key) != null;
    }

    /** 中に入れるか（持ち主か、持ち主が「だれでも入れる」にしている） */
    public boolean canEnter(String key, long playerId) {
        JsonNode h = world.layerValue("houses", key);
        if (h == null) return false;
        return isOwner(key, playerId) || h.path("open").asBoolean(false);
    }

    public boolean isOpen(String key) {
        JsonNode h = world.layerValue("houses", key);
        return h != null && h.path("open").asBoolean(false);
    }

    public ObjectNode view(String key, boolean owner) {
        ObjectNode v = om.valueToTree(book.view(key, owner));
        String layout = v.path("layout").asText(null);
        if (layout != null) {
            try { v.set("layout", om.readTree(layout)); } catch (Exception e) { v.putNull("layout"); }
        }
        return v;
    }

    /** 変わった家を保存する（行が無ければ作る） */
    public void save(String key) {
        HouseBook.Room r = book.copy(key);
        if (r == null) return;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("layout", r.layout());
        doc.put("storage", r.shared());
        doc.put("priv", r.priv());
        String json;
        try { json = om.writeValueAsString(doc); } catch (Exception e) { return; }
        Timestamp now = Timestamp.from(Instant.now());
        int up = jdbc.update("UPDATE house_room SET doc = ?, updated_at = ? WHERE house_key = ?", json, now, key);
        if (up == 0) jdbc.update("INSERT INTO house_room (house_key, doc, updated_at) VALUES (?, ?, ?)", key, json, now);
    }

    /** 共有の村をリセットしたとき、家の中身も消す */
    public void resetAll() {
        book.clear();
        jdbc.update("DELETE FROM house_room");
    }
}
