package jp.komorebi.admin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jp.komorebi.player.PlayerService;
import jp.komorebi.ws.GameSocketHandler;

/** 管理人室：プレイヤーに物をプレゼントする（/admin/gift）。アイテムの一覧は admin-items.json（tools/items/gen_items.py で作る） */
@Controller
@RequestMapping("/admin/gift")
public class AdminGiftController {

    public record Item(String id, String name, String kind) { }
    public record Row(Long id, String name, boolean online, int pending) { }

    private final PlayerService players;
    private final ItemGiftService gifts;
    private final GameSocketHandler socket;
    private final List<Item> catalog = new ArrayList<>();

    public AdminGiftController(PlayerService players, ItemGiftService gifts, GameSocketHandler socket, ObjectMapper om) {
        this.players = players;
        this.gifts = gifts;
        this.socket = socket;
        try (InputStream in = new ClassPathResource("admin-items.json").getInputStream()) {
            for (JsonNode e : om.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8))) catalog.add(new Item(e.path(0).asText(), e.path(1).asText(), e.path(2).asText("")));
        } catch (Exception e) {
            // 一覧がなくても、ID を直接入れれば送れる
        }
    }

    private String nameOf(String id) {
        return catalog.stream().filter(i -> i.id().equals(id)).map(Item::name).findFirst().orElse(id);
    }

    @GetMapping
    public String page(Model model) {
        var pend = gifts.pending();
        model.addAttribute("rows", players.listAll().stream().map(p -> new Row(p.getId(), p.getName(), socket.isOnline(p.getId()), pend.path(String.valueOf(p.getId())).size())).toList());
        model.addAttribute("catalog", catalog);
        return "admin/gift";
    }

    @PostMapping("/{id}")
    public String give(@PathVariable Long id, @RequestParam(defaultValue = "") String item, @RequestParam(defaultValue = "1") String n,
                       @RequestParam(required = false) String text, RedirectAttributes ra) {
        String typed = item.strip();
        // 名前で入れられたときは、一覧から ID にする（「ベッド」→ f_bed）
        String it = catalog.stream().anyMatch(x -> x.id().equals(typed)) ? typed
            : catalog.stream().filter(x -> x.name().equals(typed)).map(Item::id).findFirst().orElse(typed);
        int c;
        try { c = Integer.parseInt(n.strip()); } catch (NumberFormatException e) { c = 0; }
        if (!ItemGiftService.validId(it)) { ra.addFlashAttribute("msg", "物を一覧からえらんでください"); return "redirect:/admin/gift"; }
        if (c < 1 || c > ItemGiftService.MAX_N) { ra.addFlashAttribute("msg", "個数は 1〜999 で入れてください"); return "redirect:/admin/gift"; }
        if (!catalog.isEmpty() && catalog.stream().noneMatch(x -> x.id().equals(it))) { ra.addFlashAttribute("msg", "「" + it + "」は一覧にない物です。一覧からえらんでください"); return "redirect:/admin/gift"; }
        var p = players.listAll().stream().filter(x -> x.getId().equals(id)).findFirst();
        if (p.isEmpty()) { ra.addFlashAttribute("msg", "そのプレイヤーは見つかりませんでした"); return "redirect:/admin/gift"; }
        boolean now = gifts.give(id, it, c, text);
        ra.addFlashAttribute("msg", String.format("%s さんに %s ×%d を送りました。%s", p.get().getName(), nameOf(it), c,
            now ? "つないでいたので、すぐ届きました" : "いまはつないでいないので、次に村に来たときに届きます"));
        return "redirect:/admin/gift";
    }
}
