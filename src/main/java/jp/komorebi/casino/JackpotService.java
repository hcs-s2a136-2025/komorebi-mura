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

    public static final long START = 3000, MAX = 50_000_000;

    private final SharedWorldService world;

    public JackpotService(SharedWorldService world) {
        this.world = world;
    }

    public synchronized long pot() {
        JsonNode n = world.getSystem("_jackpot");
        return n != null && n.isNumber() ? n.asLong() : START;
    }

    public synchronized long add(long n) {
        long v = Math.min(MAX, pot() + Math.max(0, n));
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
