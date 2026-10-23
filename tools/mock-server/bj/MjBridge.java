import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jp.komorebi.casino.mj.MjGame;

/**
 * 模擬サーバー（mock.js）から、本物と同じ麻雀のルール（casino/mj/*.java）を動かすための小さな橋渡し。
 * 入力：  M<TAB>id<TAB>受付番号（同じ卓の人 → M<TAB>受付番号<TAB>idをカンマ区切り） ／  C<TAB>id<TAB>name<TAB>act<TAB>a<TAB>b  ／  T<TAB>つないでいる人のidをカンマ区切り
 * 出力：  A<TAB>json（全員へ） ／  P<TAB>id<TAB>json（その人へ） ／  Y<TAB>id<TAB>お金<TAB>説明（精算）
 */
public class MjBridge {
    public static void main(String[] args) throws Exception {
        MjGame game = new MjGame(new SecureRandom(), new MjGame.Out() {
            public void toAll(Map<String, Object> m) { BjBridge.OUT.println("A\t" + BjBridge.json(m)); }
            public void toPlayer(long id, Map<String, Object> m) { BjBridge.OUT.println("P\t" + id + "\t" + BjBridge.json(m)); }
            public void pay(long id, long money, String text) { BjBridge.OUT.println("Y\t" + id + "\t" + money + "\t" + text.replace('\t', ' ')); }
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String[] f = line.split("\t", -1);
            long now = System.currentTimeMillis();
            if (f[0].equals("M") && f.length >= 3) { StringBuilder b = new StringBuilder(); for (Long id : game.mates(Long.parseLong(f[1]))) b.append(b.length() > 0 ? "," : "").append(id); BjBridge.OUT.println("M\t" + f[2] + "\t" + b); continue; }
            if (f[0].equals("C") && f.length >= 6) game.command(Long.parseLong(f[1]), f[2], f[3], (int) BjBridge.parse(f[4]), (int) BjBridge.parse(f[5]), now);
            else if (f[0].equals("T")) {
                Set<Long> on = new HashSet<>();
                if (f.length > 1) for (String s : f[1].split(",")) if (!s.isBlank()) on.add(Long.parseLong(s.trim()));
                game.tick(now, on::contains);
            }
        }
    }
}
