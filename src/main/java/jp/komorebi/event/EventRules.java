package jp.komorebi.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * イベントのルール（Spring なし）。種類・採点・魚の表・ごほうびの書き方。
 *
 * ごほうびの書き方（カンマ区切り）：money:500 / medal:10 / item:アイテムID:個数 / wear:top|bottom|hat|acc:名前
 */
public final class EventRules {

    private EventRules() { }

    public static final Set<String> KINDS = Set.of("fishing", "easter", "halloween", "christmas", "custom");
    public static final Set<String> SCORINGS = Set.of("points", "count", "kinds", "biggest", "collect", "none");
    public static final Map<String, String> KIND_NAMES = Map.of("fishing", "釣り大会", "easter", "イースター", "halloween", "ハロウィン", "christmas", "クリスマス", "custom", "独自イベント");
    public static final Map<String, String> SCORING_NAMES = Map.of("points", "釣った魚のポイント合計", "count", "釣った数", "kinds", "釣った魚の種類", "biggest", "いちばん大きい魚",
        "collect", "拾った数", "none", "参加するだけ（順位なし）");
    private static final Pattern REWARD = Pattern.compile("(money|medal):\\d{1,7}|item:[a-z0-9_]{1,32}:\\d{1,3}|wear:(top|bottom|hat|acc):[^,:]{1,16}");

    /** 魚の値段（大会のポイント）と、ふつうの大きさ（cm） */
    public record Fish(int price, int base) { }

    public static final Map<String, Fish> FISH = Map.ofEntries(
        Map.entry("フナ", new Fish(20, 20)), Map.entry("コイ", new Fish(45, 50)), Map.entry("ナマズ", new Fish(40, 60)), Map.entry("ウナギ", new Fish(90, 70)),
        Map.entry("イワナ", new Fish(60, 30)), Map.entry("ブラックバス", new Fish(70, 45)), Map.entry("ワカサギ", new Fish(35, 12)), Map.entry("サケ", new Fish(55, 70)),
        Map.entry("ヌシ", new Fish(800, 150)), Map.entry("アジ", new Fish(25, 25)), Map.entry("タイ", new Fish(70, 45)), Map.entry("マグロ", new Fish(110, 150)),
        Map.entry("ネッタイギョ", new Fish(60, 15)), Map.entry("カツオ", new Fish(80, 60)), Map.entry("ブリ", new Fish(90, 90)), Map.entry("ヤマメ", new Fish(55, 25)),
        Map.entry("ドジョウ", new Fish(30, 15)), Map.entry("ザリガニ", new Fish(25, 12)), Map.entry("ニジマス", new Fish(65, 40)), Map.entry("サバ", new Fish(45, 40)),
        Map.entry("イカ", new Fish(70, 35)), Map.entry("タコ", new Fish(120, 60)), Map.entry("ヒラメ", new Fish(100, 55)),
        Map.entry("コバンゴイ", new Fish(1000, 80)), Map.entry("ギャクテンダイ", new Fish(1000, 70)),
        Map.entry("ヘラブナ", new Fish(80, 35)), Map.entry("アユ", new Fish(120, 22)), Map.entry("ライギョ", new Fish(160, 70)),
        Map.entry("カサゴ", new Fish(90, 25)), Map.entry("ロウニンアジ", new Fish(320, 100)), Map.entry("チョウチンアンコウ", new Fish(520, 40)));

    /** 何をして競うか：fish（釣り）/ collect（拾う）/ none（参加だけ） */
    public static String activity(String kind, String scoring) {
        if ("easter".equals(kind) || "halloween".equals(kind) || "christmas".equals(kind)) return "collect";
        if ("fishing".equals(kind)) return "fish";
        if ("collect".equals(scoring)) return "collect";
        if ("none".equals(scoring)) return "none";
        return "fish";
    }

    /** 種類に合う採点（イースターなどは拾った数、釣り大会で拾う・参加だけは選べない） */
    public static String fixScoring(String kind, String scoring) {
        if ("easter".equals(kind) || "halloween".equals(kind) || "christmas".equals(kind)) return "collect";
        if ("fishing".equals(kind) && ("collect".equals(scoring) || "none".equals(scoring) || !SCORINGS.contains(scoring))) return "points";
        return SCORINGS.contains(scoring) ? scoring : "points";
    }

    public static boolean validReward(String spec) {
        if (spec == null || spec.isBlank()) return true;
        if (spec.length() > 300) return false;
        for (String p : spec.split(",")) if (!REWARD.matcher(p.trim()).matches()) return false;
        return true;
    }

    public static String combine(String... specs) {
        List<String> out = new ArrayList<>();
        for (String s : specs) if (s != null && !s.isBlank()) for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return String.join(",", out);
    }

    /** ひとりぶんの記録 */
    public static final class Score {
        public long points;
        public int count, best, collected;
        public final Set<String> kinds = new TreeSet<>();

        public long value(String scoring) {
            return switch (scoring) {
                case "count" -> count;
                case "kinds" -> kinds.size();
                case "biggest" -> best;
                case "collect" -> collected;
                case "none" -> 0;
                default -> points;
            };
        }

        public String save() {
            return points + "," + count + "," + best + "," + collected + "|" + String.join(";", kinds);
        }

        public static Score load(String s) {
            Score r = new Score();
            if (s == null || s.isBlank()) return r;
            try {
                String[] a = s.split("\\|", -1), n = a[0].split(",");
                r.points = Long.parseLong(n[0]); r.count = Integer.parseInt(n[1]); r.best = Integer.parseInt(n[2]); r.collected = Integer.parseInt(n[3]);
                if (a.length > 1 && !a[1].isBlank()) for (String k : a[1].split(";")) r.kinds.add(k);
            } catch (Exception e) { /* 読めない記録は 0 から */ }
            return r;
        }
    }

    /** 釣った魚を記録する。表にない魚・ありえない大きさは記録しない */
    public static boolean addFish(Score s, String fish, int size) {
        Fish f = FISH.get(fish);
        if (f == null || size < 1 || size > Math.round(f.base() * 1.35) + 1) return false;
        s.points += f.price();
        s.count++;
        s.kinds.add(fish);
        s.best = Math.max(s.best, size);
        return true;
    }
}
