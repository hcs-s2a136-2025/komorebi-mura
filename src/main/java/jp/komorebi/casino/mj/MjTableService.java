package jp.komorebi.casino.mj;

import java.security.SecureRandom;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * みんなの麻雀の卓を WebSocket とつなぐ係。ルールと進行は {@link MjGame}。
 * 精算は本人に mjPay で伝える。そのとき本人がつないでいなければ取っておいて、次の welcome で渡す（共有の村の "_mjpay"）。
 */
@Service
public class MjTableService implements WelcomeContributor {

    private static final String PENDING = "_mjpay";

    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectMapper om;
    private final SharedWorldService world;
    private final MjGame game;

    public MjTableService(ObjectProvider<GameSocketHandler> hub, ObjectMapper om, SharedWorldService world) {
        this.hub = hub;
        this.om = om;
        this.world = world;
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
        game.command(from.playerId(), from.name(), msg.path("act").asText(""), msg.path("a").asInt(0), msg.path("b").asInt(0), System.currentTimeMillis());
    }

    @Scheduled(fixedDelay = 400)
    public void tick() {
        final GameSocketHandler h = hub.getIfAvailable();
        if (h == null) return;
        game.tick(System.currentTimeMillis(), id -> h.isOnline(id));
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
