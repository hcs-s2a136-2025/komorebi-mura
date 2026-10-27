package jp.komorebi.village;

import java.util.function.Supplier;

/**
 * いま処理している「村」。WebSocket のメッセージを処理するあいだ・つないだとき・管理人室の画面を作るあいだなどに決まる。
 * 村ごとにデータを持つサービスは {@link #current()} で、どの村の分を使うかを決める（決まっていなければ 1番の村）。
 * スケジューラーなど、だれのためでもない処理は、{@link #run(long, Runnable)} で村ごとに回す。
 */
public final class Villages {

    /** いままでの村（こもれび村） */
    public static final long MAIN = 1L;

    private static final ThreadLocal<Long> CUR = new ThreadLocal<>();

    private Villages() { }

    /** いまの村。決まっていなければ 1番の村 */
    public static long current() {
        Long v = CUR.get();
        return v == null ? MAIN : v;
    }

    /** いまの村が決まっているか（決まっていなければ null）。全員に送るときに、村をしぼるかどうかに使う */
    public static Long scoped() {
        return CUR.get();
    }

    public static void run(long village, Runnable r) {
        Long prev = CUR.get();
        CUR.set(village);
        try { r.run(); } finally { if (prev == null) CUR.remove(); else CUR.set(prev); }
    }

    public static <T> T in(long village, Supplier<T> s) {
        Long prev = CUR.get();
        CUR.set(village);
        try { return s.get(); } finally { if (prev == null) CUR.remove(); else CUR.set(prev); }
    }

    /** 村を決めずに（全部の村に向けて）動かす */
    public static void global(Runnable r) {
        Long prev = CUR.get();
        CUR.remove();
        try { r.run(); } finally { if (prev != null) CUR.set(prev); }
    }

    /** 管理人室の画面のあいだだけ使う（AdminVillageInterceptor から） */
    public static void set(Long village) {
        if (village == null) CUR.remove(); else CUR.set(village);
    }
}
