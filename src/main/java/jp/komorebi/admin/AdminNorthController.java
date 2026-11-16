package jp.komorebi.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.komorebi.village.Villages;
import jp.komorebi.world.SharedWorldService;
import jp.komorebi.ws.GameSocketHandler;

/** 管理人室：北の土地（y 44〜）に建てられた物の一覧を見て、消したり（飾り・建物・家・みんなの建物）動かしたり（家以外）する（/admin/north） */
@Controller
@RequestMapping("/admin/north")
public class AdminNorthController {

    private final SharedWorldService world;
    private final GameSocketHandler socket;

    public AdminNorthController(SharedWorldService world, GameSocketHandler socket) {
        this.world = world;
        this.socket = socket;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("items", world.northItems());
        return "admin/north";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam String layer, @RequestParam String key, RedirectAttributes ra) {
        return act(layer, key, false, 0, 0, ra, "消しました");
    }

    @PostMapping("/move")
    public String move(@RequestParam String layer, @RequestParam String key, @RequestParam int x, @RequestParam int y, RedirectAttributes ra) {
        return act(layer, key, true, x, y, ra, "動かしました");
    }

    private String act(String layer, String key, boolean move, int x, int y, RedirectAttributes ra, String ok) {
        String[] reason = { "" };
        ArrayNode list = world.adminNorth(layer, key, move, x, y, reason);
        if (list == null) { ra.addFlashAttribute("msg", reason[0]); return "redirect:/admin/north"; }
        ObjectNode out = socket.json().createObjectNode().put("type", "edits").put("by", 0);
        out.set("list", list);
        socket.broadcastTo(Villages.current(), out, null);
        world.persist();
        ra.addFlashAttribute("msg", ok + "（いまつないでいる人の画面にも反映ずみ）");
        return "redirect:/admin/north";
    }
}
