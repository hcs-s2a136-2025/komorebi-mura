package jp.komorebi.village;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongFunction;

/** 村ごとに1つずつ持つもの（卓・ゲームの進行など）。はじめて使ったときに作る */
public final class PerVillage<T> {

    private final Map<Long, T> map = new ConcurrentHashMap<>();
    private final LongFunction<T> make;

    public PerVillage(LongFunction<T> make) {
        this.make = make;
    }

    /** いまの村の分 */
    public T get() {
        return of(Villages.current());
    }

    public T of(long village) {
        return map.computeIfAbsent(village, make::apply);
    }

    /** もう作ってある村の分だけ、その村を「いまの村」にして回す */
    public void each(BiConsumer<Long, T> f) {
        for (Map.Entry<Long, T> e : map.entrySet()) Villages.run(e.getKey(), () -> f.accept(e.getKey(), e.getValue()));
    }

    public Map<Long, T> all() {
        return map;
    }
}
