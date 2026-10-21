package jp.komorebi.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jp.komorebi.board.BoardService;
import jp.komorebi.board.BoardService.PostView;
import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.ws.GameSocketHandler;

/** 伝言板（書き込みが残る掲示板）。本人確認は X-Player-Token */
@RestController
@RequestMapping("/api/board")
public class BoardController {

    private final BoardService board;
    private final PlayerService players;
    private final RateLimiter limiter;
    private final GameSocketHandler hub;

    public BoardController(BoardService board, PlayerService players, RateLimiter limiter, GameSocketHandler hub) {
        this.board = board;
        this.players = players;
        this.limiter = limiter;
        this.hub = hub;
    }

    public record PostRequest(@NotBlank @Size(max = 400) String body) {
    }

    @GetMapping
    public List<PostView> list(@RequestHeader("X-Player-Token") String token) {
        players.require(token);
        return board.latest();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostView post(@RequestHeader("X-Player-Token") String token, @Valid @RequestBody PostRequest req) {
        Player p = players.require(token);
        limiter.check("board:" + p.getId(), 6, 10 * 60 * 1000L);
        PostView v = board.post(p, req.body());
        hub.broadcast(hub.json().createObjectNode().put("type", "board").put("id", v.id()).put("name", v.name()), null);
        return v;
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-Player-Token") String token, @PathVariable Long id) {
        board.deleteOwn(players.require(token), id);
    }
}
