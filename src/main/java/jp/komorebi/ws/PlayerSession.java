package jp.komorebi.ws;

import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 1人ぶんの接続と、いまの位置・見た目 */
public class PlayerSession {
    final WebSocketSession ws;
    volatile Long playerId;
    volatile String name;
    volatile String map = "village";
    volatile int x;
    volatile int y;
    volatile String dir = "down";
    volatile ObjectNode cfg;
    long windowStart;
    int windowCount;

    PlayerSession(WebSocketSession ws) {
        this.ws = ws;
    }

    public boolean isAuthed() { return playerId != null; }
    public Long playerId() { return playerId; }
    public String name() { return name; }
    public String map() { return map; }
    public int x() { return x; }
    public int y() { return y; }
    public String dir() { return dir; }
    public ObjectNode cfg() { return cfg; }

    public void moveTo(String map, int x, int y, String dir) {
        this.map = map;
        this.x = x;
        this.y = y;
        this.dir = dir;
    }

    public void setCfg(ObjectNode cfg) { this.cfg = cfg; }

    /** 1秒あたりのメッセージ数を数えて、多すぎたら false */
    synchronized boolean allow(int perSecond) {
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000) { windowStart = now; windowCount = 0; }
        return ++windowCount <= perSecond;
    }
}
