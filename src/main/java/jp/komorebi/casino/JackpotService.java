package jp.komorebi.casino;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/** スロットのジャックポットを、つないでいる全員で1つにする。賭け金の3%がみんなの分から積み立てられる */
@Service
public class JackpotService implements WelcomeContributor {

    /**
     * 上限なし（v0.87.4 から BigInteger。前は画面が正しく持てる 2^53−1 で止めていて、そこから増えなくなった）。
     * 保存は数字の文字列（前の数のままでも読める）。画面には数（pot・だいたい）と文字列（ps・ぴったり）の両方を送る
     */
    public static final long START = 3000;
    /** 1回に足せるいちばん大きい額（変な数で壊さないため） */
    public static final long MAX_ADD = 9_007_199_254_740_991L;
    private static final java.math.BigInteger LIMIT = java.math.BigInteger.TEN.pow(60);

    private final SharedWorldService world;

    public JackpotService(SharedWorldService world) {
        this.world = world;
    }

    public synchronized java.math.BigInteger pot() {
        JsonNode n = world.getSystem("_jackpot");
        if (n == null) return java.math.BigInteger.valueOf(START);
        if (n.isNumber()) return n.bigIntegerValue();
        try { return new java.math.BigInteger(n.asText()); } catch (NumberFormatException e) { return java.math.BigInteger.valueOf(START); }
    }

    public synchronized java.math.BigInteger add(long n) {
        java.math.BigInteger v = pot().add(java.math.BigInteger.valueOf(Math.max(0, Math.min(n, MAX_ADD))));
        if (v.compareTo(LIMIT) > 0) v = LIMIT;
        world.putSystem("_jackpot", TextNode.valueOf(v.toString()));
        return v;
    }

    /** 当たった人の分を払い出して、最初の額に戻す。戻り値は当たった額 */
    public synchronized java.math.BigInteger win() {
        java.math.BigInteger v = pot();
        world.putSystem("_jackpot", TextNode.valueOf(String.valueOf(START)));
        return v;
    }

    /** 画面に送る形：pot（数・大きいとだいたい）と ps（ぴったりの文字列） */
    public static void put(ObjectNode o, String key, String keyS, java.math.BigInteger v) {
        if (v.bitLength() <= 53) o.put(key, v.longValue()); else o.put(key, v.doubleValue());
        o.put(keyS, v.toString());
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        put(welcome, "jackpot", "jackpotS", pot());
    }
}
