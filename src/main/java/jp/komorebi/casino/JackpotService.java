package jp.komorebi.casino;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.PlayerSession;
import jp.komorebi.ws.WelcomeContributor;

/** スロットのジャックポットを、つないでいる全員で1つにする。賭け金の3%がみんなの分から積み立てられる */
@Service
public class JackpotService implements WelcomeContributor {

    /**
     * 上限なし（v0.82.3）。MAX は数が壊れないための安全な値だけ：画面（JavaScript）が正しく持てるいちばん大きい整数（2^53−1・約9,007兆）
     */
    public static final long START = 3000, MAX = 9_007_199_254_740_991L;

    private final SharedWorldService world;

    public JackpotService(SharedWorldService world) {
        this.world = world;
    }

    public synchronized long pot() {
        JsonNode n = world.getSystem("_jackpot");
        return n != null && n.isNumber() ? n.asLong() : START;
    }

    public synchronized long add(long n) {
        long p = pot(), add = Math.max(0, n);
        long v = add > MAX - p ? MAX : p + add;
        world.putSystem("_jackpot", LongNode.valueOf(v));
        return v;
    }

    /** 当たった人の分を払い出して、最初の額に戻す。戻り値は当たった額 */
    public synchronized long win() {
        long v = pot();
        world.putSystem("_jackpot", LongNode.valueOf(START));
        return v;
    }

    @Override
    public void contribute(PlayerSession session, ObjectNode welcome) {
        welcome.put("jackpot", pot());
    }
}
