package jp.komorebi.raid;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 管理人室：討伐クエスト（/admin/raid）。どの魔物を・どこに・いつ・どれくらいの強さで出すかを決める */
@Controller
@RequestMapping("/admin/raid")
public class AdminRaidController {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(JST);

    private final RaidService raid;

    public AdminRaidController(RaidService raid) {
        this.raid = raid;
    }

    /** 画面に出す形（テンプレートでは JSON や static のクラスにさわらない。Thymeleaf 3.1 は T(...) を使えないため） */
    public record Plan(int id, String kind, String place, String start, int mins, long hp, int reward) { }
    public record Cur(String kind, String place, long hp, long max, String end, int buff) { }
    public record Hit(String name, long dmg) { }
    public record Last(String kind, boolean won, int people) { }

    private String name(Map<String, String> m, String k) { return m.getOrDefault(k, k); }
    private String time(long ms) { return SHOW.format(Instant.ofEpochMilli(ms)); }
    private List<Hit> hits(JsonNode top) {
        List<Hit> l = new ArrayList<>();
        for (JsonNode t : top) l.add(new Hit(t.path("name").asText(""), t.path("dmg").asLong()));
        return l;
    }

    @GetMapping
    public String page(Model model) {
        ObjectNode s = raid.summary();
        List<Plan> plans = new ArrayList<>();
        for (JsonNode p : s.path("plans")) plans.add(new Plan(p.path("id").asInt(), name(RaidService.KINDS, p.path("kind").asText()), name(RaidService.PLACES, p.path("place").asText()),
            time(p.path("start").asLong()), p.path("mins").asInt(), p.path("hp").asLong(), p.path("reward").asInt()));
        model.addAttribute("plans", plans);
        JsonNode c = s.path("cur");
        if (c.isObject()) {
            model.addAttribute("cur", new Cur(name(RaidService.KINDS, c.path("kind").asText()), name(RaidService.PLACES, c.path("place").asText()), c.path("hp").asLong(), c.path("max").asLong(), time(c.path("end").asLong()), c.path("buff").asInt()));
            model.addAttribute("curTop", hits(c.path("top")));
        }
        JsonNode l = s.path("last");
        if (l.isObject()) {
            model.addAttribute("last", new Last(name(RaidService.KINDS, l.path("kind").asText()), l.path("won").asBoolean(), l.path("people").asInt()));
            model.addAttribute("lastTop", hits(l.path("top")));
        }
        model.addAttribute("buff", s.path("buff").asInt(0));
        model.addAttribute("kinds", RaidService.KINDS);
        model.addAttribute("places", RaidService.PLACES);
        model.addAttribute("defStart", LocalDateTime.now(JST).withSecond(0).withNano(0).plusMinutes(10).format(IN));
        return "admin/raid";
    }

    @PostMapping
    public String create(@RequestParam String kind, @RequestParam String place, @RequestParam String start,
                         @RequestParam(defaultValue = "15") int mins, @RequestParam(defaultValue = "8000") int hp,
                         @RequestParam(defaultValue = "3000") int reward, RedirectAttributes ra) {
        Instant s;
        try { s = LocalDateTime.parse(start, IN).atZone(JST).toInstant(); }
        catch (Exception ex) { ra.addFlashAttribute("msg", "日時の書き方がちがいます"); return "redirect:/admin/raid"; }
        String err = raid.plan(kind, place, s, mins, hp, reward);
        ra.addFlashAttribute("msg", err != null ? err : "討伐の予定を入れました（" + RaidService.KINDS.get(kind) + "・" + SHOW.format(s) + "）");
        return "redirect:/admin/raid";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable int id, RedirectAttributes ra) {
        String err = raid.startNow(id);
        ra.addFlashAttribute("msg", err != null ? err : "討伐を始めました");
        return "redirect:/admin/raid";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable int id, RedirectAttributes ra) {
        String err = raid.cancel(id);
        ra.addFlashAttribute("msg", err != null ? err : "予定を取り消しました");
        return "redirect:/admin/raid";
    }

    @PostMapping("/stop")
    public String stop(RedirectAttributes ra) {
        String err = raid.stop();
        ra.addFlashAttribute("msg", err != null ? err : "討伐をやめました（魔物は帰っていきました）");
        return "redirect:/admin/raid";
    }
}
