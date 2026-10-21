package jp.komorebi.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 画面用の小さな整形（テンプレートから ${@fmt.time(...)} で使う） */
@Component("fmt")
public class Fmt {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"));
    private static final Map<String, String> LABEL = Map.of(
        "BUG", "不具合", "IDEA", "ほしい機能", "OTHER", "その他",
        "OPEN", "未対応", "WIP", "対応中", "DONE", "対応済み", "CLOSED", "クローズ");

    public String time(Instant i) {
        return i == null ? "" : F.format(i);
    }

    public String label(String key) {
        return LABEL.getOrDefault(key, key);
    }
}
