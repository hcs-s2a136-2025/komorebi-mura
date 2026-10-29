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
    private static final Pattern COS = Pattern.compile("[a-z0-9]{1,12}");
    private static final Logger log = LoggerFactory.getLogger(FarmService.class);
    private static final int MAX_DOC = 60_000;
    private static final Duration KEEP = Duration.ofDays(60);
    private final ObjectMapper om;
    private final JdbcTemplate jdbc;
    private final Map<Long, ObjectNode> farms = new ConcurrentHashMap<>();
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();

    private final jp.komorebi.village.VillageService villages;

    public FarmService(ObjectMapper om, JdbcTemplate jdbc, jp.komorebi.village.VillageService villages) {
        this.om = om;
        this.jdbc = jdbc;
        this.villages = villages;
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
            if (pets.size() >= 20) break;
            String b = p.path(0).asText("");
            if (!ID.matcher(b).matches()) continue;
            ArrayNode o = pets.addArray(); o.add(b); o.add(Math.max(0, Math.min(20, p.path(1).asInt(0)))); o.add(clean(p.path(2).asText(""), 12));
            // 4つ目：着せ替えの服（"h3ct25k0" のような短い英数字。v0.56.0〜）
            String cos = p.path(3).asText("");
            if (COS.matcher(cos).matches()) o.add(cos);
        }
        // 盗まれた作物：持ち主の画面がまだ知らずに送ってきたら、その作物は外しておく。もう消えていたら（知らせが届いた）、記録も消す
        ObjectNode old = farms.get(playerId);
        ArrayNode pend = old == null ? null : (ArrayNode) (old.get("stolen") instanceof ArrayNode a ? a : null);
        if (pend != null && !pend.isEmpty()) {
            ArrayNode keep = om.createArrayNode();
            for (JsonNode s : pend) {
                boolean still = false;
                for (int i = crops.size() - 1; i >= 0; i--) {
                    JsonNode c = crops.get(i);
                    if (c.path(0).asInt() == s.path(0).asInt() && c.path(1).asInt() == s.path(1).asInt() && c.path(2).asText().equals(s.path(2).asText()) && c.path(3).asInt() >= 2) { crops.remove(i); still = true; }
                }
                if (still) keep.add(s);
            }
            if (!keep.isEmpty()) f.set("stolen", keep);
        }
        ObjectNode prev = farms.put(playerId, f);
        if (prev == null || !prev.equals(f)) dirty.add(playerId);
        return f;
    }

    /**
     * ほかの人の、収穫どきの作物を盗む。うまくいったら作物の種類を返す（だめなら null）。
     * 持ち主の畑のようすから外して、「盗まれた」記録を残す（持ち主がつないでいなくても、次につないだときに知らせる）。
     */
    public synchronized String steal(long owner, int x, int y, String thiefName) {
        ObjectNode f = farms.get(owner);
        if (f == null || !(f.get("crops") instanceof ArrayNode crops)) return null;
        for (int i = 0; i < crops.size(); i++) {
            JsonNode c = crops.get(i);
            if (c.path(0).asInt(-1) != x || c.path(1).asInt(-1) != y || c.path(3).asInt(0) < 2) continue;
            String t = c.path(2).asText("");
            crops.remove(i);
            ArrayNode st = f.get("stolen") instanceof ArrayNode a ? a : f.putArray("stolen");
            if (st.size() < 100) { ArrayNode e = st.addArray(); e.add(x); e.add(y); e.add(t); e.add(clean(thiefName, 16)); }
            dirty.add(owner);
            return t;
        }
        return null;
    }

    public ObjectNode get(long owner) {
        return farms.get(owner);
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
        long v = session.village();
        for (Map.Entry<Long, ObjectNode> e : farms.entrySet()) if (!e.getKey().equals(session.playerId()) && villages.of(e.getKey()) == v) list.add(e.getValue());
        ObjectNode mine = session.playerId() == null ? null : farms.get(session.playerId());
        if (mine != null && mine.get("stolen") instanceof ArrayNode st && !st.isEmpty()) welcome.set("farmStolen", st.deepCopy());
    }

    static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
