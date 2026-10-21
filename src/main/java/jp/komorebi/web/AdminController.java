package jp.komorebi.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.komorebi.board.BoardService;
import jp.komorebi.clock.ClockService;
import jp.komorebi.config.KomorebiProperties;
import jp.komorebi.feedback.FeedbackService;
import jp.komorebi.feedback.FeedbackThread.Status;
import jp.komorebi.casino.JackpotService;
import jp.komorebi.house.HouseService;
import jp.komorebi.player.PlayerService;
import jp.komorebi.save.SaveService;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.ChatHandler;
import jp.komorebi.ws.GameSocketHandler;

/** 管理人用の画面（ログインが必要） */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final FeedbackService feedback;
    private final GameSocketHandler socket;
    private final KomorebiProperties props;
    private final SharedWorldService world;
    private final ChatHandler chat;
    private final BoardService board;
    private final ClockService clock;
    private final PlayerService players;
    private final SaveService saves;
    private final JackpotService jackpot;
    private final HouseService houses;

    /** 管理人室の「プレイヤー」一覧の1行 */
    public record PlayerRow(Long id, String name, boolean hasPass, Instant lastSeen, int saveSize) {
    }

    public AdminController(FeedbackService feedback, GameSocketHandler socket, KomorebiProperties props,
                           SharedWorldService world, ChatHandler chat, BoardService board, ClockService clock,
                           PlayerService players, SaveService saves, JackpotService jackpot, HouseService houses) {
        this.houses = houses;
        this.clock = clock;
        this.players = players;
        this.saves = saves;
        this.jackpot = jackpot;
        this.feedback = feedback;
        this.socket = socket;
        this.props = props;
        this.world = world;
        this.chat = chat;
        this.board = board;
    }

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    @GetMapping
    public String index(@RequestParam(required = false) String status, @RequestParam(required = false) String category, Model model) {
        model.addAttribute("threads", feedback.listAll(status, category));
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("category", category == null ? "" : category);
        model.addAttribute("unread", feedback.adminUnreadCount());
        model.addAttribute("online", socket.onlinePlayers());
        model.addAttribute("version", props.version());
        model.addAttribute("worldStats", world.stats());
        model.addAttribute("chat", chat.recentLines());
        model.addAttribute("posts", board.latest());
        Map<Long, Integer> sizes = saves.sizes();
        List<PlayerRow> rows = players.listAll().stream()
            .map(p -> new PlayerRow(p.getId(), p.getName(), p.hasPass(), p.getLastSeen(), sizes.getOrDefault(p.getId(), 0))).toList();
        model.addAttribute("players", rows);
        model.addAttribute("jackpot", jackpot.pot());
        int t = (int) Math.min(1439, clock.time());
        model.addAttribute("clockText", String.format("%d日目 %d:%02d（現実の1秒でゲーム内%.1f分・1日 約%d分）", clock.day(), t / 60, t % 60, clock.rate(), Math.round(1080 / clock.rate() / 60)));
        return "admin/index";
    }

    @GetMapping("/threads/{id}")
    public String thread(@PathVariable Long id, Model model) {
        model.addAttribute("t", feedback.openAsAdmin(id));
        model.addAttribute("statuses", Status.values());
        return "admin/thread";
    }

    @PostMapping("/threads/{id}/reply")
    public String reply(@PathVariable Long id, @RequestParam String body, RedirectAttributes ra) {
        if (body == null || body.isBlank()) {
            ra.addFlashAttribute("msg", "返事が空っぽです");
        } else {
            feedback.adminReply(id, body);
            ra.addFlashAttribute("msg", "返事を送りました");
        }
        return "redirect:/admin/threads/" + id;
    }

    @PostMapping("/threads/{id}/status")
    public String status(@PathVariable Long id, @RequestParam Status status, RedirectAttributes ra) {
        feedback.changeStatus(id, status);
        ra.addFlashAttribute("msg", "状態を変えました");
        return "redirect:/admin/threads/" + id;
    }

    @PostMapping("/world/reset")
    public String resetWorld(@RequestParam(required = false) String confirm, RedirectAttributes ra) {
        if (!"リセット".equals(confirm)) {
            ra.addFlashAttribute("msg", "確認の欄に「リセット」と入力してください");
            return "redirect:/admin";
        }
        houses.resetAll();
        world.reset();
        socket.broadcast(socket.json().createObjectNode().put("type", "worldReset"), null);
        ra.addFlashAttribute("msg", "共有の村を最初からにしました。キャラクター（持ち物・お金・見た目・自分の家）はそのままです。建てた家の家具と箱の中身は、持ち主が次につないだときに自分の家の収納箱へ戻ります");
        return "redirect:/admin";
    }

    @PostMapping("/clock/morning")
    public String morning(RedirectAttributes ra) {
        clock.morningNow();
        ra.addFlashAttribute("msg", "村を次の日の朝にしました");
        return "redirect:/admin";
    }

    @PostMapping("/board/{id}/delete")
    public String deletePost(@PathVariable Long id, RedirectAttributes ra) {
        board.adminDelete(id);
        ra.addFlashAttribute("msg", "伝言板の書き込みを消しました");
        return "redirect:/admin";
    }

    @PostMapping("/players/{id}/pass/reset")
    public String resetPass(@PathVariable Long id, RedirectAttributes ra) {
        players.resetPass(id);
        ra.addFlashAttribute("msg", "合言葉を消しました。次にそのなまえで入った人が、新しい合言葉を決められます");
        return "redirect:/admin";
    }

    @PostMapping("/players/{id}/save/delete")
    public String deleteSave(@PathVariable Long id, @RequestParam(required = false) String confirm, RedirectAttributes ra) {
        if (!"けす".equals(confirm)) {
            ra.addFlashAttribute("msg", "セーブを消すときは、確認の欄に「けす」と入力してください");
            return "redirect:/admin";
        }
        saves.delete(id);
        ra.addFlashAttribute("msg", "その人のサーバーのセーブを消しました");
        return "redirect:/admin";
    }

    @PostMapping("/announce")
    public String announce(@RequestParam String text, RedirectAttributes ra) {
        if (text != null && !text.isBlank()) {
            socket.announce(text.strip().length() > 120 ? text.strip().substring(0, 120) : text.strip());
            ra.addFlashAttribute("msg", "つないでいる人にお知らせを送りました");
        }
        return "redirect:/admin";
    }
}
