package jp.komorebi.save;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * プレイヤーごとのセーブデータ（持ち物・お金・畑・着せ替えなど、ゲームの saveText() の中身）。
 * 中身はゲーム側が作るJSONの文字列をそのまま預かるだけ。消すのは本人（ゲームの「データを消す」）か管理人だけ。
 */
@Service
public class SaveService {

    public static final int MAX_DOC = 900_000;

    private final JdbcTemplate jdbc;

    public SaveService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Save(String doc, long rev, Instant savedAt) {
    }

    public Optional<Save> load(Long playerId) {
        List<Save> list = jdbc.query("SELECT doc, rev, saved_at FROM player_save WHERE player_id = ?",
            (rs, i) -> new Save(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant()), playerId);
        return list.stream().findFirst();
    }

    public Instant store(Long playerId, String doc, long rev) {
        if (doc == null || doc.isBlank() || !doc.strip().startsWith("{")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "セーブデータの形がちがいます");
        if (doc.length() > MAX_DOC) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "セーブデータが大きすぎます");
        Timestamp now = Timestamp.from(Instant.now());
        int up = jdbc.update("UPDATE player_save SET doc = ?, rev = ?, saved_at = ? WHERE player_id = ?", doc, rev, now, playerId);
        if (up == 0) jdbc.update("INSERT INTO player_save (player_id, doc, rev, saved_at) VALUES (?, ?, ?, ?)", playerId, doc, rev, now);
        return now.toInstant();
    }

    public void delete(Long playerId) {
        jdbc.update("DELETE FROM player_save WHERE player_id = ?", playerId);
    }

    /** 管理人室用：だれのセーブがどのくらいの大きさか */
    public Map<Long, Integer> sizes() {
        Map<Long, Integer> m = new HashMap<>();
        RowCallbackHandler row = rs -> m.put(rs.getLong(1), rs.getInt(2));
        jdbc.query("SELECT player_id, LENGTH(doc) FROM player_save", row);
        return m;
    }
}
