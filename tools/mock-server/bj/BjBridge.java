import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.komorebi.casino.BjGame;

/**
 * 模擬サーバー（mock.js）から、本物と同じBJのルール（BjGame.java）を動かすための小さな橋渡し。
 * 入力（1行ずつ）：  M<TAB>id<TAB>受付番号（同じ卓の人 → M<TAB>受付番号<TAB>idをカンマ区切り） ／  C<TAB>id<TAB>name<TAB>act<TAB>amount  ／  T<TAB>つないでいる人のidをカンマ区切り
 * 出力：  A<TAB>json（全員へ） ／  P<TAB>id<TAB>json（その人へ）
 */
public class BjBridge {
    static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        BjGame game = new BjGame(new SecureRandom(), new BjGame.Out() {
            public void toAll(Map<String, Object> m) { OUT.println("A\t" + json(m)); }
            public void toPlayer(long id, Map<String, Object> m) { OUT.println("P\t" + id + "\t" + json(m)); }
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String[] f = line.split("\t", -1);
            long now = System.currentTimeMillis();
            if (f[0].equals("M") && f.length >= 3) { StringBuilder b = new StringBuilder(); for (Long id : game.mates(Long.parseLong(f[1]))) b.append(b.length() > 0 ? "," : "").append(id); BjBridge.OUT.println("M\t" + f[2] + "\t" + b); continue; }
            if (f[0].equals("C") && f.length >= 5) game.command(Long.parseLong(f[1]), f[2], f[3], parse(f[4]), now);
            else if (f[0].equals("T")) {
                Set<Long> on = new HashSet<>();
                if (f.length > 1) for (String s : f[1].split(",")) if (!s.isBlank()) on.add(Long.parseLong(s.trim()));
                game.tick(now, on::contains);
            }
        }
    }

    static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    static String json(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            for (Map.Entry<?, ?> e : m.entrySet()) { if (b.length() > 1) b.append(','); b.append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue())); }
            return b.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (Object x : l) { if (b.length() > 1) b.append(','); b.append(json(x)); }
            return b.append(']').toString();
        }
        return json(o.toString());
    }
}
