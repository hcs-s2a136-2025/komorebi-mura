package jp.komorebi.village;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/**
 * 管理人の「見学」。管理人室で村を選んで「見学する」を押すと、10分だけ使える見学の合言葉（トークン）を作る。
 * ゲームを ?watch=トークン で開くと、その村に「見えない人」としてつながる（ほかの人には見えない・何も変えられない・セーブしない）。
 */
@Service
public class WatchService {

    private static final long TTL_MS = 10 * 60 * 1000L;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, long[]> tokens = new ConcurrentHashMap<>();
    /** 見学している人の番号（本物のプレイヤーとかぶらないように、マイナスの数） */
    private final AtomicLong nextId = new AtomicLong(-1000);

    public String issue(long village) {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(t -> t[1] < now);
        byte[] raw = new byte[18];
        random.nextBytes(raw);
        String t = "w_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(t, new long[] { village, now + TTL_MS });
        return t;
    }

    /** 使える見学トークンなら、その村の番号。ちがえば -1 */
    public long village(String token) {
        if (token == null || !token.startsWith("w_")) return -1;
        long[] t = tokens.get(token);
        if (t == null || t[1] < System.currentTimeMillis()) return -1;
        return t[0];
    }

    public long newWatcherId() {
        return nextId.decrementAndGet();
    }

    public static boolean isWatchToken(String token) {
        return token != null && token.startsWith("w_");
    }
}
