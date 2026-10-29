package jp.komorebi.clock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.village.PerVillage;
import jp.komorebi.village.VillageService;
import jp.komorebi.village.Villages;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/**
 * 村の時計。日付・時刻・天気をサーバーが決めて、みんなに同じものを配る。
 * - 時刻は 6:00（360分）から 24:00（1440分）まで、TIME_RATE（ゲーム内の分 / 現実の1秒）で進む
 * - だれかが眠るか、24:00 になると、全員が次の日の朝になる
 * - 時計は村ごと（共有の村の "_clock" に、その村の日にちと天気を持つ）
 * - 天気は7日先まで先に決めておく（"fc"）。朝になったら先頭を使って、うしろに1日足す。スマホの「天気予報屋」が見る（forecast()）
 */
@Service
public class ClockService implements WelcomeContributor {

    private static final String[] SEASONS = { "春", "夏", "秋", "冬" };
    private static final long SLEEP_COOLDOWN_MS = 60_000;

    private final SharedWorldService world;
    private final ObjectMapper om;
    private final ObjectProvider<GameSocketHandler> hub;
    private final ObjectProvider<VillageService> villages;
    private final double rate;
    private final Random random = new Random();

    /** 1つの村の時計 */
    private static final class C {
        int day = 1;
        long dayStartMs;
        String weather = "sun";
        long lastSync;
        final ArrayDeque<String> fc = new ArrayDeque<>();
    }

    static final int FORECAST = 7;

    private final PerVillage<C> clocks;

    public ClockService(SharedWorldService world, ObjectMapper om, ObjectProvider<GameSocketHandler> hub, ObjectProvider<VillageService> villages,
                        @Value("${komorebi.time-rate:1.0}") double rate) {
        this.world = world;
        this.om = om;
        this.hub = hub;
        this.villages = villages;
        this.rate = rate > 0 ? Math.min(rate, 20) : 1.0;
        this.clocks = new PerVillage<>(v -> Villages.in(v, () -> {
            C c = new C();
            JsonNode saved = world.getSystem("_clock");
            c.day = saved != null ? Math.max(1, saved.path("day").asInt(1)) : 1;
            c.weather = saved != null ? saved.path("weather").asText("sun") : "sun";
            if (saved != null) for (JsonNode w : saved.path("fc")) if (c.fc.size() < FORECAST && WEATHERS.contains(w.asText(""))) c.fc.add(w.asText());
            fill(c);
            // サーバーを再起動したら、その日の朝から再開する
            c.dayStartMs = System.currentTimeMillis();
            save(c);
            return c;
        }));
    }

    private C c() {
        return clocks.get();
    }

    public double time() {
        C c = c();
        synchronized (c) { return 360 + (System.currentTimeMillis() - c.dayStartMs) / 1000.0 * rate; }
    }

    public ObjectNode state() {
        C c = c();
        synchronized (c) { return om.createObjectNode().put("day", c.day).put("time", Math.min(1439.9, time())).put("rate", rate).put("weather", c.weather); }
    }

    /** 眠ったとき。直前に朝になったばかりなら何もしない（連続で日付が飛ぶのを防ぐ） */
    public boolean sleep(PlayerSession who) {
        C c = c();
        synchronized (c) {
            if (System.currentTimeMillis() - c.dayStartMs < SLEEP_COOLDOWN_MS) return false;
        }
        advance("sleep", who);
        return true;
    }

    /** 管理人室から朝にする（いまの村） */
    public void morningNow() {
        advance("admin", null);
    }

    private void advance(String reason, PlayerSession who) {
        C c = c();
        ObjectNode msg;
        synchronized (c) {
            c.day++;
            c.dayStartMs = System.currentTimeMillis();
            fill(c);
            c.weather = c.fc.isEmpty() ? rollWeather(c.day) : c.fc.poll();
            fill(c);
            save(c);
            msg = state().put("type", "clock").put("reason", reason);
            if (who != null) msg.put("by", who.playerId()).put("byName", who.name());
        }
        GameSocketHandler h = hub.getIfAvailable();
        if (h != null) h.broadcastTo(Villages.current(), msg, null);
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        VillageService vs = villages.getIfAvailable();
        for (long v : vs == null ? java.util.List.of(Villages.MAIN) : vs.ids()) Villages.run(v, this::tickOne);
    }

    private void tickOne() {
        C c = c();
        if (time() >= 1440) {
            advance("midnight", null);
            return;
        }
        long now = System.currentTimeMillis();
        boolean sync;
        synchronized (c) { sync = now - c.lastSync > 30_000; if (sync) c.lastSync = now; }
        if (sync) {
            GameSocketHandler h = hub.getIfAvailable();
            if (h != null) h.broadcastTo(Villages.current(), state().put("type", "clock").put("reason", "sync"), null);
        }
    }

    static final java.util.Set<String> WEATHERS = java.util.Set.of("sun", "rain", "storm", "snow");

    /** 7日先までの天気をうめる（c.fc の先頭が明日） */
    private void fill(C c) {
        while (c.fc.size() < FORECAST) c.fc.add(rollWeather(c.day + 1 + c.fc.size()));
    }

    private void save(C c) {
        ObjectNode o = om.createObjectNode().put("day", c.day).put("weather", c.weather);
        var arr = o.putArray("fc");
        for (String w : c.fc) arr.add(w);
        world.putSystem("_clock", o);
    }

    /** いまの村の、明日から7日分の天気（天気予報屋） */
    public List<String> forecast() {
        C c = c();
        synchronized (c) { fill(c); return new ArrayList<>(c.fc); }
    }

    private String rollWeather(int d) {
        String s = SEASONS[((d - 1) / 7) % 4];
        double r = random.nextDouble();
        if (s.equals("冬")) return r < .5 ? "snow" : "sun";
        if (s.equals("夏")) return r < .6 ? "sun" : r < .85 ? "rain" : "storm";
        return r < .62 ? "sun" : r < .9 ? "rain" : "storm";
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.set("clock", state());
    }

    public int day() { C c = c(); synchronized (c) { return c.day; } }
    public double rate() { return rate; }
}
