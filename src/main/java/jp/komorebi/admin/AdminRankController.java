package jp.komorebi.admin;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.komorebi.phone.GameRankService;
import jp.komorebi.player.PlayerService;

/**
 * 管理人室：スマホのゲームのランキングに出さない人をえらぶ。
 * 出さない人のスコアは消さないので、いつでも戻せる。本人の画面では、ほかの人と同じようにランキングに出る。
 */
@Controller
@RequestMapping("/admin/rank")
public class AdminRankController {

    private final PlayerService players;
    private final GameRankService ranks;

    public AdminRankController(PlayerService players, GameRankService ranks) {
        this.players = players;
        this.ranks = ranks;
    }

    public record Row(Long id, String name, boolean hidden) { }

    @GetMapping
    public String page(Model model) {
        Set<Long> hidden = ranks.hidden();
        List<Row> rows = players.listAll().stream().map(p -> new Row(p.getId(), p.getName(), hidden.contains(p.getId()))).toList();
        model.addAttribute("rows", rows);
        return "admin/rank";
    }

    @PostMapping("/{id}")
    public String toggle(@PathVariable long id, @RequestParam(defaultValue = "0") int hide, RedirectAttributes ra) {
        var p = players.listAll().stream().filter(x -> x.getId() == id).findFirst();
        if (p.isEmpty()) { ra.addFlashAttribute("msg", "この村の人ではありません"); return "redirect:/admin/rank"; }
        ranks.setHidden(id, hide == 1);
        ra.addFlashAttribute("msg", p.get().getName() + (hide == 1 ? " をほかの人のランキングに出さないようにしました" : " をランキングに戻しました"));
        return "redirect:/admin/rank";
    }
}
