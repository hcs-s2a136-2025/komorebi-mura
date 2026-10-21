package jp.komorebi.web;

import java.util.List;
import java.util.Map;

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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jp.komorebi.config.KomorebiProperties;
import jp.komorebi.feedback.FeedbackService;
import jp.komorebi.feedback.FeedbackThread.Category;
import jp.komorebi.feedback.FeedbackViews.ThreadView;
import jp.komorebi.player.Player;
import jp.komorebi.player.PlayerService;
import jp.komorebi.ws.GameSocketHandler;

/**
 * ゲーム（ブラウザ）から呼ぶAPI。
 * 本人確認はヘッダー X-Player-Token（参加したときにもらうトークン）で行う。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final String TOKEN = "X-Player-Token";

    private final PlayerService players;
    private final FeedbackService feedback;
    private final KomorebiProperties props;
    private final RateLimiter limiter;
    private final GameSocketHandler socket;

    public ApiController(PlayerService players, FeedbackService feedback, KomorebiProperties props, RateLimiter limiter, GameSocketHandler socket) {
        this.players = players;
        this.feedback = feedback;
        this.props = props;
        this.limiter = limiter;
        this.socket = socket;
    }

    public record JoinRequest(@Size(max = 64) String inviteCode, @Size(max = 32) String name, @Size(max = 64) String pass) {
    }

    public record NameRequest(@NotBlank @Size(max = 32) String name) {
    }

    public record FeedbackRequest(@NotBlank @Pattern(regexp = "BUG|IDEA|OTHER") String category,
                                  @NotBlank @Size(max = 60) String title,
                                  @NotBlank @Size(max = 2000) String body,
                                  @Size(max = 1000) String context) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 2000) String body) {
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        return Map.of("app", "komorebi", "version", props.version(), "online", socket.onlineCount());
    }

    @PostMapping("/join")
    public Map<String, Object> join(@Valid @RequestBody JoinRequest req, jakarta.servlet.http.HttpServletRequest http) {
        // Cloudflare のトンネル経由だと、みんな同じ住所に見えるので、元の住所のヘッダーがあればそれを使う
        String ip = http.getHeader("CF-Connecting-IP");
        limiter.check("join:" + (ip != null && !ip.isBlank() ? ip.strip() : http.getRemoteAddr()), 20, 60 * 60 * 1000L);
        PlayerService.Joined j = players.join(req.inviteCode(), req.name(), req.pass());
        return Map.of("token", j.token(), "id", j.player().getId(), "name", j.player().getName(), "created", j.created());
    }

    @GetMapping("/session")
    public Map<String, Object> session(@RequestHeader(TOKEN) String token) {
        Player p = players.require(token);
        return Map.of("id", p.getId(), "name", p.getName(), "version", props.version(), "unread", feedback.unreadFor(p), "hasPass", p.hasPass());
    }

    @PostMapping("/me/name")
    public Map<String, Object> rename(@RequestHeader(TOKEN) String token, @Valid @RequestBody NameRequest req) {
        Player p = players.rename(token, req.name());
        socket.renamed(p.getId(), p.getName());
        return Map.of("id", p.getId(), "name", p.getName());
    }

    @GetMapping("/feedback")
    public List<ThreadView> myFeedback(@RequestHeader(TOKEN) String token) {
        return feedback.listFor(players.require(token));
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreadView send(@RequestHeader(TOKEN) String token, @Valid @RequestBody FeedbackRequest req) {
        Player p = players.require(token);
        limiter.check("fb:" + p.getId(), 10, 60 * 60 * 1000L);
        return feedback.create(p, Category.valueOf(req.category()), req.title(), req.body(), req.context());
    }

    @PostMapping("/feedback/{id}/messages")
    public ThreadView reply(@RequestHeader(TOKEN) String token, @PathVariable Long id, @Valid @RequestBody ReplyRequest req) {
        Player p = players.require(token);
        limiter.check("msg:" + p.getId(), 40, 60 * 60 * 1000L);
        return feedback.playerReply(p, id, req.body());
    }

    @PostMapping("/feedback/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@RequestHeader(TOKEN) String token, @PathVariable Long id) {
        feedback.markReadByPlayer(players.require(token), id);
    }
}
