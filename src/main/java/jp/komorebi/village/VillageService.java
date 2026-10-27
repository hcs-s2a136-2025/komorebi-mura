package jp.komorebi.village;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 村の一覧。1番の村（こもれび村）は最初からあり、ほかの村は参加画面の「新しい村を作る」で作る（1番をのぞいて最大 {@link #MAX_EXTRA} こ）。
 * 村の合言葉（code）は 6文字の英大文字と数字。知っている人だけがその村に入れる。1番の村には合言葉はいらない。
 * プレイヤーはどれか1つの村にだけ住む（player.village_id）。ほかの村で遊ぶときは、その村で新しいキャラクターを作る。
 */
@Service
public class VillageService {

    public static final int MAX_EXTRA = 10;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public record Village(long id, String name, String code, Long ownerId, Instant createdAt) {
        public boolean main() { return id == Villages.MAIN; }
    }

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Long> villageOf = new ConcurrentHashMap<>();
    private volatile List<Village> cache;

    public VillageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Village> list() {
        List<Village> c = cache;
        if (c != null) return c;
        List<Village> out = new ArrayList<>();
        jdbc.query("SELECT id, name, code, owner_id, created_at FROM village ORDER BY id", rs -> {
            long o = rs.getLong(4);
            out.add(new Village(rs.getLong(1), rs.getString(2), rs.getString(3), rs.wasNull() ? null : o, rs.getTimestamp(5).toInstant()));
        });
        if (out.stream().noneMatch(v -> v.id() == Villages.MAIN)) out.add(0, new Village(Villages.MAIN, "こもれび村", "MAIN", null, Instant.EPOCH));
        cache = List.copyOf(out);
        return cache;
    }

    public List<Long> ids() {
        return list().stream().map(Village::id).toList();
    }

    public Optional<Village> find(long id) {
        return list().stream().filter(v -> v.id() == id).findFirst();
    }

    public String name(long id) {
        return find(id).map(Village::name).orElse("こもれび村");
    }

    /** 村の合言葉から（大文字・小文字・前後の空白は気にしない） */
    public Optional<Village> byCode(String code) {
        String c = normCode(code);
        if (c.isEmpty()) return Optional.empty();
        return list().stream().filter(v -> !v.main() && v.code().equals(c)).findFirst();
    }

    public static String normCode(String code) {
        return code == null ? "" : code.strip().toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    public static String cleanName(String name) {
        String n = name == null ? "" : name.strip().replaceAll("[\\p{Cntrl}<>|]", "");
        return n.codePointCount(0, n.length()) > 10 ? n.substring(0, n.offsetByCodePoints(0, 10)) : n;
    }

    /** 新しい村を作る（村長はあとで setOwner でつける） */
    public synchronized Village create(String name) {
        String n = cleanName(name);
        if (n.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "村の名前を入れてね");
        long extra = list().stream().filter(v -> !v.main()).count();
        if (extra >= MAX_EXTRA) throw new ResponseStatusException(HttpStatus.CONFLICT, "村はもういっぱい（" + MAX_EXTRA + "こまで）。管理人に相談してね");
        if (list().stream().anyMatch(v -> v.name().equals(n))) throw new ResponseStatusException(HttpStatus.CONFLICT, "その名前の村はもうあるよ");
        String code = newCode();
        jdbc.update("INSERT INTO village (name, code, owner_id, created_at) VALUES (?, ?, NULL, ?)", n, code, Timestamp.from(Instant.now()));
        cache = null;
        return list().stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow();
    }

    public synchronized void setOwner(long village, long playerId) {
        jdbc.update("UPDATE village SET owner_id = ? WHERE id = ?", playerId, village);
        cache = null;
    }

    public synchronized Village rename(long village, String name) {
        String n = cleanName(name);
        if (n.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "村の名前を入れてね");
        if (list().stream().anyMatch(v -> v.id() != village && v.name().equals(n))) throw new ResponseStatusException(HttpStatus.CONFLICT, "その名前の村はもうあるよ");
        jdbc.update("UPDATE village SET name = ? WHERE id = ?", n, village);
        cache = null;
        return find(village).orElseThrow();
    }

    /** 村の合言葉を作り直す（前の合言葉では入れなくなる。もう住んでいる人はそのまま） */
    public synchronized Village newCode(long village) {
        if (village == Villages.MAIN) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "こもれび村には合言葉はいらないよ");
        jdbc.update("UPDATE village SET code = ? WHERE id = ?", newCode(), village);
        cache = null;
        return find(village).orElseThrow();
    }

    private String newCode() {
        for (int t = 0; t < 50; t++) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            String c = b.toString();
            if (list().stream().noneMatch(v -> v.code().equals(c))) return c;
        }
        throw new IllegalStateException("合言葉を作れませんでした");
    }

    /** その人が住んでいる村（わからなければ 1番） */
    public long of(Long playerId) {
        if (playerId == null || playerId <= 0) return Villages.MAIN;
        return villageOf.computeIfAbsent(playerId, id -> {
            List<Long> r = jdbc.queryForList("SELECT village_id FROM player WHERE id = ?", Long.class, id);
            return r.isEmpty() || r.get(0) == null ? Villages.MAIN : r.get(0);
        });
    }

    public void remember(long playerId, long village) {
        villageOf.put(playerId, village);
    }

    /** 2人が同じ村か */
    public boolean same(Long a, Long b) {
        return a != null && b != null && of(a) == of(b);
    }
}
