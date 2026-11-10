package jp.komorebi.voice;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.village.Villages;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * 通話アプリ（v0.74.0）の「部屋」。1対1の電話も、グループ通話も、同じ部屋のしくみ（6人まで・村ごと・メモリだけ）。
 * <ul>
 *   <li>{"type":"call","a":"ring","to"} … 部屋を作って（まだ部屋にいなければ）相手を呼ぶ。相手に {"type":"call","ev":"ring","room","from","name","members"}</li>
 *   <li>{"a":"join","room"} … 呼ばれた部屋に入る（呼ばれていないと入れない）。部屋のみんなに {"ev":"room","room","members":[{id,name}]}</li>
 *   <li>{"a":"decline","room"} … 断る。呼んだ人に {"ev":"declined","room","id","name"}</li>
 *   <li>{"a":"leave"} … 部屋を出る。のこった人に room（1人になっても部屋はのこる。だれもいなくなったら消える）</li>
 * </ul>
 * 声のやりとりは RtcHandler（部屋の人どうしで、番号の小さいほうがつなぎはじめる）。
 */
@Component
public class CallHandler implements MessageHandler {

    static final int MAX = 6;
    static final long RING_MS = 45_000;

    static final class Room {
        final String id;
        final long village;
        final Set<Long> members = new LinkedHashSet<>();
        final Map<Long, String> names = new ConcurrentHashMap<>();
        final Map<Long, Long> invited = new ConcurrentHashMap<>();
        Room(String id, long village) { this.id = id; this.village = village; }
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<Long, String> where = new ConcurrentHashMap<>();
    private final ObjectProvider<GameSocketHandler> hubs;
    private final Map<Long, Long> last = new ConcurrentHashMap<>();
    private int seq;

    public CallHandler(ObjectProvider<GameSocketHandler> hubs) {
        this.hubs = hubs;
    }

    @Override
    public String type() {
        return "call";
    }

    private ObjectNode out(GameSocketHandler hub) {
        return hub.json().createObjectNode().put("type", "call");
    }

    @Override
    public synchronized void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.playerId() <= 0 || from.watcher()) return;
        long me = from.playerId(), now = System.currentTimeMillis();
        String a = msg.path("a").asText("");
        Long prev = last.put(me, now);
        if (prev != null && now - prev < 250 && !"leave".equals(a)) return;
        String nm = from.name() == null ? "" : from.name();
        switch (a) {
            case "ring" -> {
                long to = msg.path("to").asLong(0);
                if (to <= 0 || to == me || !hub.onlineHere(to)) { hub.send(from, out(hub).put("ng", "その人は、いまつないでいないみたい")); return; }
                Room r = rooms.get(where.getOrDefault(me, ""));
                if (r == null) {
                    leave(me, hub);
                    r = new Room("c" + (++seq) + Long.toString(now % 100000, 36), Villages.current());
                    rooms.put(r.id, r);
                    r.members.add(me); r.names.put(me, nm); where.put(me, r.id);
                    sendRoom(r, hub);
                }
                if (r.members.contains(to)) return;
                if (r.members.size() >= MAX) { hub.send(from, out(hub).put("ng", "通話は " + MAX + " 人までだよ")); return; }
                r.invited.put(to, now);
                ObjectNode m = out(hub).put("ev", "ring").put("room", r.id).put("from", me).put("name", nm);
                m.set("members", members(r, hub));
                hub.sendToPlayer(to, m);
                hub.send(from, out(hub).put("ok", "ring").put("to", to).put("room", r.id));
            }
            case "join" -> {
                Room r = rooms.get(msg.path("room").asText(""));
                Long inv = r == null ? null : r.invited.remove(me);
                if (r == null || inv == null || now - inv > RING_MS || r.village != Villages.current()) { hub.send(from, out(hub).put("ng", "その通話は、もうおわったみたい")); return; }
                if (r.members.size() >= MAX) { hub.send(from, out(hub).put("ng", "通話は " + MAX + " 人までだよ")); return; }
                if (!r.id.equals(where.get(me))) leave(me, hub);
                r.members.add(me); r.names.put(me, nm); where.put(me, r.id);
                sendRoom(r, hub);
            }
            case "decline" -> {
                Room r = rooms.get(msg.path("room").asText(""));
                if (r == null || r.invited.remove(me) == null) return;
                for (long id : r.members) hub.sendToPlayer(id, out(hub).put("ev", "declined").put("room", r.id).put("id", me).put("name", nm));
            }
            case "leave" -> leave(me, hub);
            case "look" -> { Room r = rooms.get(where.getOrDefault(me, "")); if (r != null) sendRoom(r, hub); else hub.send(from, out(hub).put("ev", "room").put("room", "").set("members", hub.json().createArrayNode())); }
            default -> { }
        }
    }

    private ArrayNode members(Room r, GameSocketHandler hub) {
        ArrayNode arr = hub.json().createArrayNode();
        for (long id : r.members) arr.addObject().put("id", id).put("name", r.names.getOrDefault(id, ""));
        return arr;
    }

    private void sendRoom(Room r, GameSocketHandler hub) {
        ObjectNode m = out(hub).put("ev", "room").put("room", r.id);
        m.set("members", members(r, hub));
        for (long id : r.members) hub.sendToPlayer(id, m);
    }

    private void leave(long me, GameSocketHandler hub) {
        String rid = where.remove(me);
        Room r = rid == null ? null : rooms.get(rid);
        if (r == null) return;
        r.members.remove(me);
        if (r.members.isEmpty()) { rooms.remove(r.id); return; }
        sendRoom(r, hub);
    }

    /** 10秒ごと：つないでいない人を部屋から出す・古い呼び出しを消す */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        GameSocketHandler hub = hubs.getIfAvailable();
        if (hub == null) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (Room r : new ArrayList<>(rooms.values())) {
                Villages.run(r.village, () -> {
                    List<Long> gone = new ArrayList<>();
                    for (long id : r.members) if (!hub.onlineHere(id)) gone.add(id);
                    for (long id : gone) leave(id, hub);
                    r.invited.values().removeIf(t -> now - t > RING_MS);
                });
            }
        }
    }
}
