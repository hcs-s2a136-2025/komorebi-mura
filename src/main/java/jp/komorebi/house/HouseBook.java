package jp.komorebi.house;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 建てた家の中身（家具の並び・壁紙・床）と、収納箱の中身を持っておくクラス。
 * Spring にもデータベースにも依存しない、ルールだけの部分（保存は {@link HouseService} がする）。
 *
 * - layout：家具の並びなど。持ち主だけが書きかえられる（JSONの文字列のまま預かる）
 * - shared：みんなの収納箱。家に入れる人ならだれでも出し入れできる
 * - priv：鍵つきの箱。持ち主だけが出し入れできる
 */
public final class HouseBook {

    public static final int MAX_LAYOUT = 30_000, MAX_KINDS = 60;
    public static final long MAX_COUNT = 99_999, MAX_MOVE = 9_999;
    private static final Pattern KEY = Pattern.compile("\\d{1,3},\\d{1,3}");
    private static final Pattern ITEM = Pattern.compile("[\\p{L}\\p{N}_:\\-]{1,32}");

    public static final class Room {
        String layout;
        final Map<String, Long> shared = new LinkedHashMap<>();
        final Map<String, Long> priv = new LinkedHashMap<>();

        public String layout() { return layout; }
        public Map<String, Long> shared() { return shared; }
        public Map<String, Long> priv() { return priv; }
    }

    private final Map<String, Room> rooms = new LinkedHashMap<>();

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public static boolean validItem(String id) {
        return id != null && ITEM.matcher(id).matches();
    }

    /** データベースから読んだものを戻すとき用 */
    public synchronized void restore(String key, String layout, Map<String, Long> shared, Map<String, Long> priv) {
        if (!validKey(key)) return;
        Room r = new Room();
        r.layout = layout;
        if (shared != null) shared.forEach((k, v) -> { if (validItem(k) && v != null && v > 0) r.shared.put(k, Math.min(MAX_COUNT, v)); });
        if (priv != null) priv.forEach((k, v) -> { if (validItem(k) && v != null && v > 0) r.priv.put(k, Math.min(MAX_COUNT, v)); });
        rooms.put(key, r);
    }

    public synchronized boolean exists(String key) {
        return rooms.containsKey(key);
    }

    /**
     * 家具の並びを書く（持ち主かどうかの確認は呼ぶ側でする）。
     * seedShared / seedPriv は、その家がサーバーに初めて来たときだけ入れる（前の版で各自の端末にあった箱の中身を引っ越す）。
     * @return 書けたら true
     */
    public synchronized boolean putLayout(String key, String layout, Map<String, Long> seedShared, Map<String, Long> seedPriv) {
        if (!validKey(key) || layout == null || layout.length() > MAX_LAYOUT) return false;
        Room r = rooms.get(key);
        boolean fresh = r == null;
        if (fresh) { r = new Room(); rooms.put(key, r); }
        r.layout = layout;
        if (fresh) {
            if (seedShared != null) seedShared.forEach((k, v) -> add(rooms.get(key).shared, k, v));
            if (seedPriv != null) seedPriv.forEach((k, v) -> add(rooms.get(key).priv, k, v));
        }
        return true;
    }

    /** 箱に入れる。@return 入れたあとの数。入れられなかったら -1 */
    public synchronized long put(String key, boolean priv, String id, long n) {
        if (!validKey(key) || !validItem(id) || n < 1 || n > MAX_MOVE) return -1;
        Room r = rooms.computeIfAbsent(key, k -> new Room());
        return add(priv ? r.priv : r.shared, id, n);
    }

    /** 箱から出す。@return 出したあとの残りの数。足りなかったら -1 */
    public synchronized long take(String key, boolean priv, String id, long n) {
        if (!validKey(key) || !validItem(id) || n < 1 || n > MAX_MOVE) return -1;
        Room r = rooms.get(key);
        if (r == null) return -1;
        Map<String, Long> box = priv ? r.priv : r.shared;
        long have = box.getOrDefault(id, 0L);
        if (have < n) return -1;
        if (have == n) box.remove(id); else box.put(id, have - n);
        return have - n;
    }

    private static long add(Map<String, Long> box, String id, Long n) {
        if (!validItem(id) || n == null || n < 1) return -1;
        if (!box.containsKey(id) && box.size() >= MAX_KINDS) return -1;
        long v = Math.min(MAX_COUNT, box.getOrDefault(id, 0L) + n);
        box.put(id, v);
        return v;
    }

    /** 画面に送る形。owner=false なら鍵つきの箱の中身は入れない */
    public synchronized Map<String, Object> view(String key, boolean owner) {
        Room r = rooms.get(key);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layout", r == null ? null : r.layout);
        m.put("storage", list(r == null ? null : r.shared));
        if (owner) m.put("priv", list(r == null ? null : r.priv));
        return m;
    }

    /** 保存用のコピー */
    public synchronized Room copy(String key) {
        Room r = rooms.get(key);
        if (r == null) return null;
        Room c = new Room();
        c.layout = r.layout;
        c.shared.putAll(r.shared);
        c.priv.putAll(r.priv);
        return c;
    }

    /** いまある家の場所の一覧（コピー） */
    public synchronized java.util.List<String> keys() {
        return new java.util.ArrayList<>(rooms.keySet());
    }

    public synchronized void clear() {
        rooms.clear();
    }

    private static List<Map<String, Object>> list(Map<String, Long> box) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (box != null) box.forEach((k, v) -> { Map<String, Object> e = new LinkedHashMap<>(); e.put("id", k); e.put("n", v); out.add(e); });
        return out;
    }
}
