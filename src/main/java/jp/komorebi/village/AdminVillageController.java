package jp.komorebi.village;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.komorebi.ws.GameSocketHandler;

/** 管理人室：村の一覧・村をえらぶ・見学する・村の名前と合言葉 */
@Controller
@RequestMapping("/admin/villages")
public class AdminVillageController {

    public record Row(long id, String name, String code, String owner, int players, int online, Instant lastSeen, Instant createdAt, boolean main) { }

    private final VillageService villages;
    private final WatchService watch;
    private final GameSocketHandler socket;
    private final JdbcTemplate jdbc;

    public AdminVillageController(VillageService villages, WatchService watch, GameSocketHandler socket, JdbcTemplate jdbc) {
        this.villages = villages;
        this.watch = watch;
        this.socket = socket;
        this.jdbc = jdbc;
    }

    @GetMapping
    public String page(Model model) {
        Map<Long, int[]> counts = new HashMap<>();
        Map<Long, Instant> last = new HashMap<>();
        jdbc.query("SELECT village_id, COUNT(*), MAX(last_seen) FROM player WHERE deleted_at IS NULL GROUP BY village_id", rs -> {
            counts.put(rs.getLong(1), new int[] { rs.getInt(2) });
            if (rs.getTimestamp(3) != null) last.put(rs.getLong(1), rs.getTimestamp(3).toInstant());
        });
        Map<Long, String> names = new HashMap<>();
        jdbc.query("SELECT v.id, p.name FROM village v JOIN player p ON p.id = v.owner_id", rs -> { names.put(rs.getLong(1), rs.getString(2)); });
        List<Row> rows = villages.list().stream().map(v -> new Row(v.id(), v.name(), v.main() ? "" : v.code(), names.getOrDefault(v.id(), v.main() ? "（はじまりの村）" : "-"),
            counts.getOrDefault(v.id(), new int[] { 0 })[0], socket.onlineCount(v.id()), last.get(v.id()), v.createdAt(), v.main())).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("max", VillageService.MAX_EXTRA);
        model.addAttribute("extra", rows.stream().filter(r -> !r.main()).count());
        return "admin/villages";
    }

    /** 見学する：10分だけ使える見学のトークンを作って、ゲームを見学モードで開く */
    @PostMapping("/{id}/watch")
    public String watch(@PathVariable long id) {
        if (villages.find(id).isEmpty()) return "redirect:/admin/villages";
        return "redirect:/?watch=" + watch.issue(id);
    }

    @PostMapping("/{id}/name")
    public String rename(@PathVariable long id, @RequestParam String name, RedirectAttributes ra) {
        try {
            villages.rename(id, name);
            socket.villageChanged(id);
            ra.addFlashAttribute("msg", "村の名前を変えました");
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("msg", e.getReason());
        }
        return "redirect:/admin/villages";
    }

    @PostMapping("/{id}/code")
    public String newCode(@PathVariable long id, RedirectAttributes ra) {
        try {
            villages.newCode(id);
            socket.villageChanged(id);
            ra.addFlashAttribute("msg", "村の合言葉を作り直しました。前の合言葉では、もう入れません（住んでいる人はそのまま）");
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("msg", e.getReason());
        }
        return "redirect:/admin/villages";
    }
}
