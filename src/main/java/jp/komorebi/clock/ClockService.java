package jp.komorebi.clock;

import java.util.Random;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 村の時計。日付・時刻・天気をサーバーが決めて、みんなに同じものを配る。
 * - 時刻は 6:00（360分）から 24:00（1440分）まで、TIME_RATE（ゲーム内の分 / 現実の1秒）で進む
 * - だれかが眠るか、24:00 になると、全員が次の日の朝になる
 */
@Service
public class ClockService implements WelcomeContributor {

    private static final String[] SEASONS = { "春", "夏", "秋", "冬" };
    private static final long SLEEP_COOLDOWN_MS = 60_000;

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hub;
    private final double rate;
    private final Random random = new Random();

    private int day = 1;
    private long dayStartMs;
    private String weather = "sun";
    private long lastSync;

    public ClockService(SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hub,
                        @Value("${komorebi.time-rate:1.0}") double rate) {
        this.world = world;
        this.om = om;
        this.hub = hub;
        this.rate = rate > 0 ? Math.min(rate, 20) : 1.0;
    }

    @PostConstruct
    void load() {
        JsonNode c = world.getSystem("_clock");
        day = c != null ? Math.max(1, c.path("day").asInt(1)) : 1;
        weather = c != null ? c.path("weather").asText("sun") : "sun";
        // サーバーを再起動したら、その日の朝から再開する
        dayStartMs = System.currentTimeMillis();
        save();
    }

    public synchronized double time() {
        return 360 + (System.currentTimeMillis() - dayStartMs) / 1000.0 * rate;
    }

    public synchronized ObjectNode state() {
        return om.createObjectNode().put("day", day).put("time", Math.min(1439.9, time())).put("rate", rate).put("weather", weather);
    }

    /** 眠ったとき。直前に朝になったばかりなら何もしない（連続で日付が飛ぶのを防ぐ） */
    public boolean sleep(PlayerSession who) {
        synchronized (this) {
            if (System.currentTimeMillis() - dayStartMs < SLEEP_COOLDOWN_MS) return false;
        }
        advance("sleep", who);
        return true;
    }

    /** 管理人室から朝にする */
    public void morningNow() {
        advance("admin", null);
    }

    private void advance(String reason, PlayerSession who) {
        ObjectNode msg;
        synchronized (this) {
            day++;
            dayStartMs = System.currentTimeMillis();
            weather = rollWeather(day);
            save();
            msg = state().put("type", "clock").put("reason", reason);
            if (who != null) msg.put("by", who.playerId()).put("byName", who.name());
        }
        GameSocketHandler h = hub.getIfAvailable();
        if (h != null) h.broadcast(msg, null);
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        boolean midnight;
        synchronized (this) {
            midnight = time() >= 1440;
        }
        if (midnight) {
            advance("midnight", null);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSync > 30_000) {
            lastSync = now;
            GameSocketHandler h = hub.getIfAvailable();
            if (h != null) h.broadcast(state().put("type", "clock").put("reason", "sync"), null);
        }
    }

    private String rollWeather(int d) {
        String s = SEASONS[((d - 1) / 7) % 4];
        double r = random.nextDouble();
        if (s.equals("冬")) return r < .5 ? "snow" : "sun";
        if (s.equals("夏")) return r < .6 ? "sun" : r < .85 ? "rain" : "storm";
        return r < .62 ? "sun" : r < .9 ? "rain" : "storm";
    }

    private void save() {
        world.putSystem("_clock", om.createObjectNode().put("day", day).put("weather", weather));
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("clock", state());
    }

    public synchronized int day() { return day; }
    public double rate() { return rate; }
}
