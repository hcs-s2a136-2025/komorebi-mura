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
    /** cash＝現金（1,000G札）の枚数、fake＝そのうち本人が偽札だとわかっている枚数、pendingCash＝届いていないお札の枚数 */
    public record Row(Long id, String name, Long money, long save, long fixed, long debt, Instant savedAt, boolean online, long pending,
                      long cash, long fake, long pendingCash) {
        public long cashG() { return cash * 1000; }
        public long bank() { return save + fixed; }
        public long net() { return (money == null ? 0 : money) + cashG() + save + fixed - debt; }
    }

    @GetMapping
    public String page(Model model) {
        var pend = gifts.pending();
        List<Row> rows = players.listAll().stream().map(p -> {
            Long money = null;
            long save = 0, fixed = 0, debt = 0, cash = 0, fake = 0;
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
                    JsonNode c = doc.path("world").path("cash78");
                    cash = Math.max(0, c.path("n").asLong(0));
                    fake = Math.max(0, Math.min(cash, c.path("fk").asLong(0)));
                } catch (Exception e) {
                    // こわれたセーブは、金額を出さない
                }
            }
            JsonNode pe = pend.path(String.valueOf(p.getId()));
            return new Row(p.getId(), p.getName(), money, save, fixed, debt, at, socket.isOnline(p.getId()), pe.path("money").asLong(0),
                cash, fake, pe.path("cash").asLong(0));
        }).toList();
        model.addAttribute("rows", rows);
        return "admin/money";
    }

    @PostMapping("/{id}")
    public String give(@PathVariable Long id, @RequestParam(required = false) String amount, @RequestParam(required = false) String sign,
                       @RequestParam(required = false) String unit, @RequestParam(required = false) String text, RedirectAttributes ra) {
        boolean bill = "bill".equals(unit);
        long v;
        try {
            v = Long.parseLong(amount == null ? "" : amount.replace(",", "").strip());
        } catch (NumberFormatException e) {
            ra.addFlashAttribute("msg", bill ? "枚数は数字で入れてください" : "金額は数字で入れてください");
            return "redirect:/admin/money";
        }
        if (bill ? (v <= 0 || v > MoneyGiftService.MAX_CASH) : (v <= 0 || v > MoneyGiftService.MAX)) {
            ra.addFlashAttribute("msg", bill ? "お札は 1〜9,999 枚で入れてください" : "金額は 1〜10,000,000 G で入れてください");
            return "redirect:/admin/money";
        }
        long money = "minus".equals(sign) ? -v : v;
        var p = players.listAll().stream().filter(x -> x.getId().equals(id)).findFirst();
        if (p.isEmpty()) {
            ra.addFlashAttribute("msg", "そのプレイヤーは見つかりませんでした");
            return "redirect:/admin/money";
        }
        boolean now = bill ? gifts.give(id, 0, money, text) : gifts.give(id, money, text);
        String what = bill ? String.format("現金（1,000G札）を %s%,d 枚", money > 0 ? "+" : "", money) : String.format("所持金を %s%,d G", money > 0 ? "+" : "", money);
        ra.addFlashAttribute("msg", String.format("%s さんの%sしました。%s", p.get().getName(), what,
            now ? "つないでいたので、すぐ反映されました" : "いまはつないでいないので、次に村に来たときに反映されます"));
        return "redirect:/admin/money";
    }

    /** この村の全員に（お詫びなど）。足すだけ */
    @PostMapping("/all")
    public String giveAll(@RequestParam(required = false) String amount, @RequestParam(required = false) String unit,
                          @RequestParam(required = false) String text, RedirectAttributes ra) {
        boolean bill = "bill".equals(unit);
        long v;
        try {
            v = Long.parseLong(amount == null ? "" : amount.replace(",", "").strip());
        } catch (NumberFormatException e) {
            ra.addFlashAttribute("msg", bill ? "枚数は数字で入れてください" : "金額は数字で入れてください");
            return "redirect:/admin/money";
        }
        if (bill ? (v <= 0 || v > MoneyGiftService.MAX_CASH) : (v <= 0 || v > MoneyGiftService.MAX)) {
            ra.addFlashAttribute("msg", bill ? "お札は 1〜9,999 枚で入れてください" : "金額は 1〜10,000,000 G で入れてください");
            return "redirect:/admin/money";
        }
        int now = 0, later = 0;
        for (var p : players.listAll()) { if (bill ? gifts.give(p.getId(), 0, v, text) : gifts.give(p.getId(), v, text)) now++; else later++; }
        ra.addFlashAttribute("msg", String.format("この村の %d 人に %s 送りました（いまつないでいる %d 人にはすぐ、ほかの %d 人には次に村に来たときに届きます）", now + later,
            bill ? String.format("1,000G札を %,d 枚ずつ", v) : String.format("+%,d G", v), now, later));
        return "redirect:/admin/money";
    }
}
