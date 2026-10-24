package jp.komorebi.web;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jp.komorebi.event.EventRules;
import jp.komorebi.event.EventService;
import jp.komorebi.sports.SportsDayService;

/**
 * 管理人室のイベント画面（/admin/events）。/admin/** は SecurityConfig で管理人だけが入れるので、
 * ここに来た操作はすべて管理人のもの（画面で隠すだけでなく、サーバーで確かめている）。
 */
@Controller
@RequestMapping("/admin/events")
public class AdminEventController {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(JST);

    private final EventService events;
    private final SportsDayService sports;

    public AdminEventController(EventService events, SportsDayService sports) {
        this.events = events;
        this.sports = sports;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("events", events.list());
        model.addAttribute("kinds", EventRules.KIND_NAMES);
        model.addAttribute("scorings", EventRules.SCORING_NAMES);
        model.addAttribute("fmt", SHOW);
        Map<Long, Integer> people = new LinkedHashMap<>();
        Map<Long, Object> top = new LinkedHashMap<>();
        for (EventService.Ev e : events.list()) { people.put(e.id(), events.participants(e.id())); top.put(e.id(), events.ranking(e.id(), 3)); }
        model.addAttribute("people", people);
        model.addAttribute("top", top);
        LocalDateTime now = LocalDateTime.now(JST).withSecond(0).withNano(0);
        model.addAttribute("defStart", now.plusMinutes(5).format(IN));
        model.addAttribute("defEnd", now.plusMinutes(35).format(IN));
        model.addAttribute("sd", sports.summary());
        return "admin/events";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        EventService.Ev e = events.find(id);
        if (e == null) return "redirect:/admin/events";
        model.addAttribute("e", e);
        model.addAttribute("fmt", SHOW);
        model.addAttribute("ranking", events.ranking(id, 200));
        return "admin/event";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam String kind, @RequestParam(defaultValue = "points") String scoring,
                         @RequestParam(defaultValue = "") String rules, @RequestParam String start, @RequestParam String end,
                         @RequestParam(defaultValue = "0") int joinFee, @RequestParam(defaultValue = "0") int minLevel,
                         @RequestParam(defaultValue = "") String r1, @RequestParam(defaultValue = "") String r2, @RequestParam(defaultValue = "") String r3,
                         @RequestParam(defaultValue = "") String all, @RequestParam(defaultValue = "") String goal, @RequestParam(defaultValue = "0") String goalScore,
                         RedirectAttributes ra) {
        Instant s, en;
        try {
            s = LocalDateTime.parse(start, IN).atZone(JST).toInstant();
            en = LocalDateTime.parse(end, IN).atZone(JST).toInstant();
        } catch (Exception ex) {
            ra.addFlashAttribute("msg", "日時の書き方がちがいます");
            return "redirect:/admin/events";
        }
        Map<String, String> rw = new LinkedHashMap<>();
        rw.put("r1", r1); rw.put("r2", r2); rw.put("r3", r3); rw.put("all", all); rw.put("goal", goal); rw.put("goalScore", goalScore);
        String err = events.create(name, kind, scoring, rules, s, en, joinFee, minLevel, rw);
        ra.addFlashAttribute("msg", err != null ? err : "イベント「" + name + "」を登録しました");
        return "redirect:/admin/events";
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable long id, RedirectAttributes ra) {
        String err = events.startNow(id);
        ra.addFlashAttribute("msg", err != null ? err : "イベントを始めました");
        return "redirect:/admin/events";
    }

    @PostMapping("/{id}/end")
    public String end(@PathVariable long id, RedirectAttributes ra) {
        String err = events.endNow(id);
        ra.addFlashAttribute("msg", err != null ? err : "イベントを終えました。順位に応じてごほうびを渡しました");
        return "redirect:/admin/events";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes ra) {
        String err = events.delete(id);
        ra.addFlashAttribute("msg", err != null ? err : "イベントを消しました");
        return "redirect:/admin/events";
    }

    // ---------- 村の運動会 ----------

    @PostMapping("/sports/open")
    public String sportsOpen(RedirectAttributes ra) {
        ra.addFlashAttribute("msg", sports.open() ? "運動会の参加受付を始めました（3分後に始まります）" : "運動会はもう開いています");
        return "redirect:/admin/events";
    }

    @PostMapping("/sports/start")
    public String sportsStart(RedirectAttributes ra) {
        String err = sports.startNow();
        ra.addFlashAttribute("msg", err != null ? err : "運動会を始めました");
        return "redirect:/admin/events";
    }

    @PostMapping("/sports/stop")
    public String sportsStop(RedirectAttributes ra) {
        sports.stop();
        ra.addFlashAttribute("msg", "運動会を中止しました");
        return "redirect:/admin/events";
    }
}
