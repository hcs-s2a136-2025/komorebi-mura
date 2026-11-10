package jp.komorebi.tourney;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 管理人室：村の大会（/admin/tourney）。パズル大会・釣り大会を開く（賞金は村から出す） */
@Controller
@RequestMapping("/admin/tourney")
public class AdminTourneyController {

    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private final TourneyService tourney;

    public AdminTourneyController(TourneyService tourney) {
        this.tourney = tourney;
    }

    /** 画面に出す形（テンプレートでは JSON にさわらない） */
    public record Row(int id, String title, String what, String host, long purse, String end, int people, String result) { }

    private Row row(JsonNode t) {
        boolean puzzle = "puzzle".equals(t.path("kind").asText());
        String what = puzzle ? "パズル：" + TourneyService.GAMES.getOrDefault(t.path("game").asText(), "?") : "釣り：" + t.path("fish").asText() + (t.path("rnd").asBoolean() ? "（ランダム）" : "");
        StringBuilder res = new StringBuilder();
        for (JsonNode p : t.path("pay")) res.append(p.path("pos").asInt()).append("着 ").append(p.path("n").asText()).append("（").append(p.path("g").asLong()).append("G）　");
        if (t.path("done").asBoolean() && res.length() == 0) res.append(t.path("cancel").asBoolean() ? "とりやめ" : "勝った人なし");
        return new Row(t.path("id").asInt(), t.path("title").asText(), what, t.path("hostName").asText(), t.path("purse").asLong(),
            SHOW.format(Instant.ofEpochMilli(t.path("end").asLong())), t.path("ent").size(), res.toString());
    }

    @GetMapping
    public String page(Model model) {
        ObjectNode s = tourney.summary();
        List<Row> cur = new ArrayList<>(), last = new ArrayList<>();
        for (JsonNode t : s.path("cur")) cur.add(row(t));
        for (JsonNode t : s.path("last")) last.add(row(t));
        model.addAttribute("cur", cur);
        model.addAttribute("last", last);
        model.addAttribute("games", TourneyService.GAMES);
        return "admin/tourney";
    }

    @PostMapping
    public String create(@RequestParam String kind, @RequestParam(defaultValue = "") String game, @RequestParam(defaultValue = "") String fish,
                         @RequestParam(defaultValue = "30") int mins, @RequestParam(defaultValue = "100000") long purse,
                         @RequestParam(defaultValue = "") String title, RedirectAttributes ra) {
        boolean random = fish.isBlank();
        String err = tourney.open(0, "管理人", kind, game, fish.trim(), random, mins, purse, title);
        ra.addFlashAttribute("msg", err != null ? err : "大会を開きました。村のみんなに知らせが出ます");
        return "redirect:/admin/tourney";
    }

    @PostMapping("/{id}/stop")
    public String stop(@PathVariable int id, RedirectAttributes ra) {
        String err = tourney.stopNow(id);
        ra.addFlashAttribute("msg", err != null ? err : "大会をおわらせました（いまの順位で賞金を払います）");
        return "redirect:/admin/tourney";
    }
}
