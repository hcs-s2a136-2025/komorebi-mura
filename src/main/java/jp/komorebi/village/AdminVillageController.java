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

    public record Row(long id, String name, String code, String owner, int players, int online, Instant lastSeen, Instant createdAt, boolean main, String size, String sizeTop, boolean sizeWarn) { }

    private final VillageService villages;
    private final WatchService watch;
    private final GameSocketHandler socket;
    private final JdbcTemplate jdbc;
    private final jp.komorebi.world.SharedWorldService shared;
    private final jp.komorebi.player.PlayerService players;
    private final jp.komorebi.save.SaveService saves;
    private final jp.komorebi.farm.FarmService farms;

    public AdminVillageController(VillageService villages, WatchService watch, GameSocketHandler socket, JdbcTemplate jdbc, jp.komorebi.world.SharedWorldService shared,
            jp.komorebi.player.PlayerService players, jp.komorebi.save.SaveService saves, jp.komorebi.farm.FarmService farms) {
        this.players = players;
        this.saves = saves;
        this.farms = farms;
        this.shared = shared;
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
        List<Row> rows = villages.list().stream().map(v -> {
            // 共有の村の保存の大きさ（上限に近づくと保存できなくなるので、先に気づけるように）
            var u = shared.usageIn(v.id());
            int pct = (int) Math.round(u.total() * 100.0 / u.max());
            String size = String.format("%,d字（上限の %d%%）", u.total(), pct);
            String top = u.top().stream().map(e -> String.format("%s %,d字", e.getKey(), e.getValue())).collect(java.util.stream.Collectors.joining("・"));
            return new Row(v.id(), v.name(), v.main() ? "" : v.code(), names.getOrDefault(v.id(), v.main() ? "（はじまりの村）" : "-"),
                counts.getOrDefault(v.id(), new int[] { 0 })[0], socket.onlineCount(v.id()), last.get(v.id()), v.createdAt(), v.main(), size, top, pct >= 70);
        }).toList();
        model.addAttribute("sizeMax", String.format("%,d", rows.isEmpty() ? 0 : shared.usageIn(rows.get(0).id()).max()));
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

    /**
     * 村を消す。確認の欄に、その村の名前を入れたときだけ。住んでいるキャラ（セーブ・畑のようす・自分だけの箱）も消して、つないでいる人は切る。
     * こもれび村は消せない。元にはもどせない。
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, @RequestParam(required = false) String confirm, RedirectAttributes ra) {
        var v = villages.find(id).orElse(null);
        if (v == null || v.main()) {
            ra.addFlashAttribute("msg", v == null ? "その村は見つからなかった" : "こもれび村は消せません");
            return "redirect:/admin/villages";
        }
        if (confirm == null || !v.name().equals(confirm.strip())) {
            ra.addFlashAttribute("msg", "村を消すときは、確認の欄にその村の名前（" + v.name() + "）を入力してください");
            return "redirect:/admin/villages";
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM player WHERE village_id = ?", Long.class, id);
        int n = 0;
        for (Long pid : ids) {
            if (players.delete(pid)) n++;
            saves.delete(pid);
            farms.forget(pid);
            jdbc.update("DELETE FROM house_room WHERE house_key IN (?, ?)", "me:" + pid, "ret:" + pid);
            socket.kickDeleted(pid);
        }
        try {
            villages.delete(id);
            shared.forgetVillage(id);
            ra.addFlashAttribute("msg", "村「" + v.name() + "」を消しました（キャラ " + n + "人ぶんも消しました）。元にはもどせません");
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("msg", e.getReason());
        }
        return "redirect:/admin/villages";
    }
}
