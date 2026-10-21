package jp.komorebi.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.save.SaveService;

/** 自分のセーブデータ（どの端末からでも続きができるように）と、合言葉の設定。本人確認は X-Player-Token */
@RestController
@RequestMapping("/api")
public class SaveController {

    private static final String TOKEN = "X-Player-Token";

    private final PlayerService players;
    private final SaveService saves;
    private final RateLimiter limiter;

    public SaveController(PlayerService players, SaveService saves, RateLimiter limiter) {
        this.players = players;
        this.saves = saves;
        this.limiter = limiter;
    }

    public record SaveRequest(@NotBlank @Size(max = SaveService.MAX_DOC) String doc, @PositiveOrZero long rev) {
    }

    public record PassRequest(@NotBlank @Size(max = 64) String pass) {
    }

    @GetMapping("/save")
    public ResponseEntity<Map<String, Object>> load(@RequestHeader(TOKEN) String token) {
        Player p = players.require(token);
        return saves.load(p.getId())
            .map(s -> ResponseEntity.ok(Map.<String, Object>of("doc", s.doc(), "rev", s.rev(), "savedAt", s.savedAt().toString())))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/save")
    public Map<String, Object> store(@RequestHeader(TOKEN) String token, @Valid @RequestBody SaveRequest req) {
        Player p = players.require(token);
        limiter.check("save:" + p.getId(), 120, 10 * 60 * 1000L);
        return Map.of("savedAt", saves.store(p.getId(), req.doc(), req.rev()).toString(), "rev", req.rev());
    }

    /** ゲームの「データを消す」から。サーバーのセーブを消す */
    @DeleteMapping("/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(TOKEN) String token) {
        saves.delete(players.require(token).getId());
    }

    @PostMapping("/me/pass")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPass(@RequestHeader(TOKEN) String token, @Valid @RequestBody PassRequest req) {
        Player p = players.require(token);
        limiter.check("pass:" + p.getId(), 10, 60 * 60 * 1000L);
        players.setPass(token, req.pass());
    }
}
