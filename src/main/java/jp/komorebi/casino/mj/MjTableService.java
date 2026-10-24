package jp.komorebi.casino.mj;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;
import jp.komorebi.casino.TableRoster;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * みんなの麻雀の卓を WebSocket とつなぐ係。ルールと進行は {@link MjGame}。
 * 精算は本人に mjPay で伝える。そのとき本人がつないでいなければ取っておいて、次の welcome で渡す（共有の村の "_mjpay"）。
 * 卓のようすは mj_state に数秒ごとに保存しておき、サーバーを再起動しても対局を続けられるようにする。
 * 読みこむのは「最初にだれかがつないできたとき」（更新のとき、古いサーバーが止まる直前に保存した最新のようすを読むため）。
 */
@Service
public class MjTableService implements WelcomeContributor, TableRoster {

    private static final String PENDING = "_mjpay";
    private static final Logger log = LoggerFactory.getLogger(MjTableService.class);

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final SharedWorldService world;
    private final MjGame game;
    private final JdbcTemplate jdbc;
    private volatile boolean loaded;
    private String lastSaved = "";
    /** 保存・読みこみ用の鍵（精算の synchronized(this) とは分けて、卓の鍵と順番がからまないように） */
    private final Object saveLock = new Object();

    public MjTableService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, SharedWorldService world, JdbcTemplate jdbc) {
        this.hub = hub;
        this.om = om;
        this.world = world;
        this.jdbc = jdbc;
        this.game = new MjGame(new SecureRandom(), new MjGame.Out() {
            @Override
            public void toAll(Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.broadcast(om.valueToTree(msg), null);
            }

            @Override
            public void toPlayer(long playerId, Map<String, Object> msg) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null) h.sendToPlayer(playerId, om.valueToTree(msg));
            }

            @Override
            public void pay(long playerId, long money, String text) {
                GameSocketHandler h = hub.getIfAvailable();
                if (h != null && h.isOnline(playerId)) {
                    h.sendToPlayer(playerId, om.createObjectNode().put("type", "mjPay").put("money", money).put("text", text));
                    return;
                }
                if (money == 0) return;
                synchronized (MjTableService.this) {
                    JsonNode cur = world.getSystem(PENDING);
                    ObjectNode p = cur instanceof ObjectNode o ? o.deepCopy() : om.createObjectNode();
                    p.put(String.valueOf(playerId), p.path(String.valueOf(playerId)).asLong(0) + money);
                    world.putSystem(PENDING, p);
                }
            }
        });
    }

    public void command(PlayerSession from, JsonNode msg) {
        ensureLoaded();
        game.command(from.playerId(), from.name(), msg.path("act").asText(""), msg.path("a").asInt(0), msg.path("b").asInt(0), System.currentTimeMillis());
    }

    @Override
    public String kind() {
        return "mj";
    }

    @Override
    public java.util.List<Long> mates(long playerId) {
        return game.mates(playerId);
    }

    @Scheduled(fixedDelay = 400)
    public void tick() {
        final GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        if (!loaded) { if (h.onlineCount() == 0) return; ensureLoaded(); }
        game.tick(System.currentTimeMillis(), id -> h.isOnline(id));
    }

    /** 保存しておいた卓を、いちどだけ読みこむ */
    private void ensureLoaded() {
        synchronized (saveLock) { loadOnce(); }
    }

    private void loadOnce() {
        if (loaded) return;
        try {
            List<String> rows = jdbc.queryForList("SELECT doc FROM mj_state WHERE id = 1", String.class);
            if (!rows.isEmpty()) {
                Map<?, ?> snap = om.readValue(rows.get(0), Map.class);
                int n = game.restore(snap, System.currentTimeMillis());
                if (n > 0) log.info("麻雀の卓を{}つ戻しました", n);
                Object ts = snap.get("tables");
                lastSaved = ts == null ? "" : om.writeValueAsString(ts);
            }
        } catch (Exception e) {
            log.warn("麻雀の卓を戻せませんでした（空から始めます）: {}", e.getMessage());
        }
        loaded = true;
    }

    /** 卓のようすが変わっていたら保存する */
    @Scheduled(fixedDelay = 3000)
    public void persist() {
        synchronized (saveLock) { saveNow(); }
    }

    private void saveNow() {
        if (!loaded) return;
        try {
            Map<String, Object> snap = game.snapshot(System.currentTimeMillis());
            String body = om.writeValueAsString(snap.get("tables"));
            if (body.equals(lastSaved)) return;
            String json = om.writeValueAsString(snap);
            Timestamp now = Timestamp.from(Instant.now());
            int up = jdbc.update("UPDATE mj_state SET doc = ?, updated_at = ? WHERE id = 1", json, now);
            if (up == 0) jdbc.update("INSERT INTO mj_state (id, doc, updated_at) VALUES (1, ?, ?)", json, now);
            lastSaved = body;
        } catch (Exception e) {
            log.warn("麻雀の卓を保存できませんでした: {}", e.getMessage());
        }
    }

    @PreDestroy
    void flush() {
        persist();
    }

    /** 留守のあいだに終わった対局の精算を、つないだときに渡す */
    @Override
    public synchronized void contribute(PlayerSession session, ObjectNode welcome) {
        JsonNode cur = world.getSystem(PENDING);
        String key = String.valueOf(session.playerId());
        if (!(cur instanceof ObjectNode o) || !o.has(key)) return;
        ObjectNode p = o.deepCopy();
        welcome.put("mjPay", p.path(key).asLong(0));
        p.remove(key);
        world.putSystem(PENDING, p);
    }

    /** 管理人室用：{卓の数, 座っている人数} */
    public int[] stats() {
        return game.stats();
    }
}
