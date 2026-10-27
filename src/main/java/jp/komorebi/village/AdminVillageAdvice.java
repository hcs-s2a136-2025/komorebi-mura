package jp.komorebi.village;

import java.util.List;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 管理人室のどの画面にも「いまえらんでいる村」と「村の一覧」を渡す（上の村の帯に使う） */
@ControllerAdvice(basePackages = { "jp.komorebi.web", "jp.komorebi.admin", "jp.komorebi.village" })
public class AdminVillageAdvice {

    private final VillageService villages;

    public AdminVillageAdvice(VillageService villages) {
        this.villages = villages;
    }

    @ModelAttribute("adminVillage")
    public VillageService.Village adminVillage() {
        return villages.find(Villages.current()).orElse(null);
    }

    @ModelAttribute("allVillages")
    public List<VillageService.Village> allVillages() {
        return villages.list();
    }
}
