package jp.komorebi.raid;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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

    @GetMapping
    public String page(Model model) {
        model.addAttribute("s", raid.summary());
        model.addAttribute("kinds", RaidService.KINDS);
        model.addAttribute("places", RaidService.PLACES);
        model.addAttribute("fmt", SHOW);
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
