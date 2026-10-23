package jp.komorebi.admin;

import java.time.Instant;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import jp.komorebi.player.PlayerService;
import jp.komorebi.save.SaveService;
import jp.komorebi.ws.GameSocketHandler;

/** 管理人室：プレイヤーの所持金の調整 */
@Controller
@RequestMapping("/admin/money")
public class AdminMoneyController {

    private final PlayerService players;
    private final SaveService saves;
    private final MoneyGiftService gifts;
    private final GameSocketHandler socket;
    private final ObjectMapper om;

    public AdminMoneyController(PlayerService players, SaveService saves, MoneyGiftService gifts, GameSocketHandler socket, ObjectMapper om) {
        this.players = players;
        this.saves = saves;
        this.gifts = gifts;
        this.socket = socket;
        this.om = om;
    }

    /** save=普通預金、fixed=定期預金の合計、debt=借金（返す額）の合計。セーブがなければ money は null */
    public record Row(Long id, String name, Long money, long save, long fixed, long debt, Instant savedAt, boolean online, long pending) {
        public long bank() { return save + fixed; }
        public long net() { return (money == null ? 0 : money) + save + fixed - debt; }
    }

    @GetMapping
    public String page(Model model) {
        var pend = gifts.pending();
        List<Row> rows = players.listAll().stream().map(p -> {
            Long money = null;
            long save = 0, fixed = 0, debt = 0;
            Instant at = null;
            var s = saves.load(p.getId());
            if (s.isPresent()) {
                at = s.get().savedAt();
                try {
                    JsonNode doc = om.readTree(s.get().doc());
                    JsonNode m = doc.path("world").path("money");
                    if (m.isNumber()) money = m.asLong();
                    JsonNode bank = doc.path("world").path("bank");
                    save = Math.max(0, bank.path("save").asLong(0));
                    for (JsonNode f : bank.path("fixed")) fixed += Math.max(0, f.path("amt").asLong(0));
                    for (JsonNode l : doc.path("world").path("loans")) debt += Math.max(0, l.path("owed").asLong(0));
                } catch (Exception e) {
                    // こわれたセーブは、金額を出さない
                }
            }
            return new Row(p.getId(), p.getName(), money, save, fixed, debt, at, socket.isOnline(p.getId()), pend.path(String.valueOf(p.getId())).path("money").asLong(0));
        }).toList();
        model.addAttribute("rows", rows);
        return "admin/money";
    }

    @PostMapping("/{id}")
    public String give(@PathVariable Long id, @RequestParam(required = false) String amount, @RequestParam(required = false) String sign,
                       @RequestParam(required = false) String text, RedirectAttributes ra) {
        long v;
        try {
            v = Long.parseLong(amount == null ? "" : amount.replace(",", "").strip());
        } catch (NumberFormatException e) {
            ra.addFlashAttribute("msg", "金額は数字で入れてください");
            return "redirect:/admin/money";
        }
        if (v <= 0 || v > MoneyGiftService.MAX) {
            ra.addFlashAttribute("msg", "金額は 1〜10,000,000 G で入れてください");
            return "redirect:/admin/money";
        }
        long money = "minus".equals(sign) ? -v : v;
        var p = players.listAll().stream().filter(x -> x.getId().equals(id)).findFirst();
        if (p.isEmpty()) {
            ra.addFlashAttribute("msg", "そのプレイヤーは見つかりませんでした");
            return "redirect:/admin/money";
        }
        boolean now = gifts.give(id, money, text);
        ra.addFlashAttribute("msg", String.format("%s さんの所持金を %s%,d G しました。%s", p.get().getName(), money > 0 ? "+" : "", money,
            now ? "つないでいたので、すぐ反映されました" : "いまはつないでいないので、次に村に来たときに反映されます"));
        return "redirect:/admin/money";
    }
}
