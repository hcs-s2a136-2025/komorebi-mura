package jp.komorebi.farm;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * みんなの畑・家畜・ペットのようす（見るだけ）。それぞれの人が自分のぶんを送ってきて、ほかの人の画面に出す。
 * 最後に送られてきたものを持っておき、あとから来た人にも welcome で渡す。持ち主がつないでいなくても見える。
 * データベース（farm_snapshot）にも少し遅れて書いておくので、サーバーを再起動しても消えない（60日送られてこなかった人は出さない）。
 */
@Service
public class FarmService implements WelcomeContributor {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,24}");
    private static final Logger log = LoggerFactory.getLogger(FarmService.class);
    private static final int MAX_DOC = 60_000;
    private static final Duration KEEP = Duration.ofDays(60);
    private final ObjectMapper om;
    private final JdbcTemplate jdbc;
    private final Map<Long, ObjectNode> farms = new ConcurrentHashMap<>();
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();

    public FarmService(ObjectMapper om, JdbcTemplate jdbc) {
        this.om = om;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void load() {
        try {
            Timestamp since = Timestamp.from(Instant.now().minus(KEEP));
            jdbc.query("SELECT f.player_id, f.doc, p.name FROM farm_snapshot f JOIN player p ON p.id = f.player_id WHERE p.deleted_at IS NULL AND f.updated_at > ?", rs -> {
                try {
                    JsonNode n = om.readTree(rs.getString(2));
                    if (n instanceof ObjectNode o) { o.put("name", clean(rs.getString(3), 16)); farms.put(rs.getLong(1), o); }
                } catch (Exception e) {
                    log.warn("畑のようすが読めなかったので飛ばします（player {}）: {}", rs.getLong(1), e.getMessage());
                }
            }, since);
        } catch (Exception e) {
            log.warn("畑のようすを読み込めませんでした。空から始めます: {}", e.getMessage());
        }
    }

    /** 変わった人のぶんだけ、まとめて書く */
    @Scheduled(fixedDelay = 20_000)
    public void persist() {
        if (dirty.isEmpty()) return;
        List<Long> ids = new ArrayList<>(dirty);
        Timestamp now = Timestamp.from(Instant.now());
        for (Long id : ids) {
            dirty.remove(id);
            ObjectNode f = farms.get(id);
            if (f == null) continue;
            String json = f.toString();
            if (json.length() > MAX_DOC) continue;
            try {
                int up = jdbc.update("UPDATE farm_snapshot SET doc = ?, updated_at = ? WHERE player_id = ?", json, now, id);
                if (up == 0) jdbc.update("INSERT INTO farm_snapshot (player_id, doc, updated_at) VALUES (?, ?, ?)", id, json, now);
            } catch (Exception e) {
                log.warn("畑のようすを保存できませんでした（player {}）: {}", id, e.getMessage());
            }
        }
    }

    @PreDestroy
    void flush() {
        persist();
    }

    /** 受け取ったものを、決まった形・大きさにそろえて取っておく。そろえたものを返す */
    public ObjectNode put(long playerId, String name, JsonNode msg) {
        ObjectNode f = om.createObjectNode();
        f.put("type", "farm");
        f.put("id", playerId);
        f.put("name", clean(name, 16));
        ArrayNode crops = f.putArray("crops");
        for (JsonNode c : msg.path("crops")) {
            if (crops.size() >= 600) break;
            int x = c.path(0).asInt(-1), y = c.path(1).asInt(-1), st = c.path(3).asInt(0);
            String t = c.path(2).asText("");
            if (x < 0 || y < 0 || x > 200 || y > 200 || !ID.matcher(t).matches()) continue;
            ArrayNode a = crops.addArray(); a.add(x); a.add(y); a.add(t); a.add(Math.max(0, Math.min(2, st)));
        }
        ArrayNode animals = f.putArray("animals");
        for (JsonNode a : msg.path("animals")) {
            if (animals.size() >= 40) break;
            String t = a.path(0).asText("");
            int x = a.path(2).asInt(-1), y = a.path(3).asInt(-1);
            if (!ID.matcher(t).matches() || x < 0 || y < 0 || x > 200 || y > 200) continue;
            ArrayNode o = animals.addArray(); o.add(t); o.add(clean(a.path(1).asText(""), 12)); o.add(x); o.add(y);
        }
        ArrayNode pets = f.putArray("pets");
        for (JsonNode p : msg.path("pets")) {
            if (pets.size() >= 8) break;
            String b = p.path(0).asText("");
            if (!ID.matcher(b).matches()) continue;
            ArrayNode o = pets.addArray(); o.add(b); o.add(Math.max(0, Math.min(20, p.path(1).asInt(0)))); o.add(clean(p.path(2).asText(""), 12));
        }
        ObjectNode prev = farms.put(playerId, f);
        if (prev == null || !prev.equals(f)) dirty.add(playerId);
        return f;
    }

    public void forget(long playerId) {
        farms.remove(playerId);
        dirty.remove(playerId);
        try {
            jdbc.update("DELETE FROM farm_snapshot WHERE player_id = ?", playerId);
        } catch (Exception e) {
            log.warn("畑のようすを消せませんでした（player {}）: {}", playerId, e.getMessage());
        }
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        ArrayNode list = welcome.putArray("farms");
        for (Map.Entry<Long, ObjectNode> e : farms.entrySet()) if (!e.getKey().equals(session.playerId())) list.add(e.getValue());
    }

    static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
