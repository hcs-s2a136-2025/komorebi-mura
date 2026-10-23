import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

import jp.komorebi.casino.race.RaceGame;

/**
 * 模擬サーバー（mock.js）から、本物と同じ競どんぐり（casino/race/RaceGame.java）を動かすための小さな橋渡し。
 * 入力：  B<TAB>id<TAB>name<TAB>runner<TAB>kind<TAB>amount ／ T<TAB>村の日付 ／ S<TAB>id<TAB>日付（新聞用のまとめ）／ L<TAB>id（いまのようす）
 * 出力：  A<TAB>json（全員へ） ／ P<TAB>id<TAB>json（その人へ） ／ S<TAB>id<TAB>日付<TAB>json
 */
public class RaceBridge {
    public static void main(String[] args) throws Exception {
        RaceGame game = new RaceGame(new SecureRandom(), new RaceGame.Out() {
            public void toAll(Map<String, Object> m) { BjBridge.OUT.println("A\t" + BjBridge.json(m)); }
            public void toPlayer(long id, Map<String, Object> m) { BjBridge.OUT.println("P\t" + id + "\t" + BjBridge.json(m)); }
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String[] f = line.split("\t", -1);
            long now = System.currentTimeMillis();
            if (f[0].equals("B") && f.length >= 6) game.bet(Long.parseLong(f[1]), f[2], (int) BjBridge.parse(f[3]), f[4], (int) BjBridge.parse(f[5]), now);
            else if (f[0].equals("T") && f.length >= 2) game.tick(now, (int) BjBridge.parse(f[1]));
            else if (f[0].equals("S") && f.length >= 3) BjBridge.OUT.println("S\t" + f[1] + "\t" + f[2] + "\t" + BjBridge.json(game.daySummary((int) BjBridge.parse(f[2]))));
            else if (f[0].equals("L") && f.length >= 2) BjBridge.OUT.println("P\t" + f[1] + "\t" + BjBridge.json(game.state(now)));
        }
    }
}
