import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jp.komorebi.snow.SnowWarGame;

/**
 * 模擬サーバー（mock.js）から、本物と同じ雪合戦の陣取り（snow/SnowWarGame.java）を動かすための小さな橋渡し。
 * 入力：  C<TAB>id<TAB>name<TAB>act<TAB>x<TAB>y<TAB>to<TAB>冬なら1  ／  T<TAB>つないでいる人のidをカンマ区切り
 * 出力：  A<TAB>json（全員へ） ／  P<TAB>id<TAB>json（その人へ）
 */
public class SwBridge {
    public static void main(String[] args) throws Exception {
        SnowWarGame game = new SnowWarGame(new SecureRandom(), new SnowWarGame.Out() {
            public void toAll(Map<String, Object> m) { BjBridge.OUT.println("A\t" + BjBridge.json(m)); }
            public void toPlayer(long id, Map<String, Object> m) { BjBridge.OUT.println("P\t" + id + "\t" + BjBridge.json(m)); }
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String[] f = line.split("\t", -1);
            long now = System.currentTimeMillis();
            if (f[0].equals("C") && f.length >= 8) game.command(Long.parseLong(f[1]), f[2], f[3], (int) BjBridge.parse(f[4]), (int) BjBridge.parse(f[5]), BjBridge.parse(f[6]), f[7].equals("1"), now);
            else if (f[0].equals("T")) {
                Set<Long> on = new HashSet<>();
                if (f.length > 1) for (String s : f[1].split(",")) if (!s.isBlank()) on.add(Long.parseLong(s.trim()));
                game.tick(now, on::contains);
            }
        }
    }
}
