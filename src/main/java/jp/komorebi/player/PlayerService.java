package jp.komorebi.player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jp.komorebi.config.KomorebiProperties;

/**
 * 参加（招待コード＋なまえ＋合言葉）とトークンでの本人確認。
 * - 同じなまえと合言葉で入ると、どの端末・どのURLからでも同じプレイヤーに戻れる
 * - 入るたびに新しいトークンを発行するので、前の端末のトークンは使えなくなる（その端末は「もう一度参加して」になる）
 * - 合言葉を決める前の古いプレイヤーは、そのなまえで最初に合言葉を入れた人のものになる
 */
@Service
public class PlayerService {

    private final PlayerRepository players;
    private final KomorebiProperties props;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public PlayerService(PlayerRepository players, KomorebiProperties props, PasswordEncoder encoder) {
        this.players = players;
        this.props = props;
        this.encoder = encoder;
    }

    /** created = 新しいプレイヤーとして作った（false なら、前のプレイヤーに戻った） */
    public record Joined(String token, Player player, boolean created) {
    }

    @Transactional
    public Joined join(String inviteCode, String name, String pass) {
        byte[] expected = props.inviteCode().getBytes(StandardCharsets.UTF_8);
        byte[] given = (inviteCode == null ? "" : inviteCode.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招待コードがちがいます");
        }
        String n = cleanName(name);
        String pw = checkPass(pass);
        List<Player> same = players.findByNameOrderByLastSeenDesc(n);
        Player p = null;
        for (Player c : same) if (c.hasPass() && encoder.matches(pw, c.getPassHash())) { p = c; break; }
        boolean created = false;
        if (p == null) {
            if (same.stream().anyMatch(Player::hasPass)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "このなまえはもう使われているか、合言葉がちがいます");
            }
            Player open = same.stream().findFirst().orElse(null);
            if (open != null) {
                p = open;
            } else {
                p = new Player(hash(newToken()), n);
                created = true;
            }
            p.setPassHash(encoder.encode(pw));
        }
        String token = newToken();
        p.setTokenHash(hash(token));
        p.touch();
        p = players.save(p);
        return new Joined(token, p, created);
    }

    @Transactional
    public Optional<Player> findByToken(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return Optional.empty();
        Optional<Player> p = players.findByTokenHash(hash(token));
        p.ifPresent(Player::touch);
        return p;
    }

    public Player require(String token) {
        return findByToken(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "もう一度参加してください"));
    }

    @Transactional
    public Player rename(String token, String name) {
        Player p = require(token);
        String n = cleanName(name);
        if (!n.equals(p.getName()) && players.findByNameOrderByLastSeenDesc(n).stream().anyMatch(c -> !c.getId().equals(p.getId()) && c.hasPass())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "そのなまえはほかの人が使っています");
        }
        p.setName(n);
        return players.save(p);
    }

    /** 合言葉を決める・変える（参加中の人だけ） */
    @Transactional
    public void setPass(String token, String pass) {
        Player p = require(token);
        String pw = checkPass(pass);
        boolean taken = players.findByNameOrderByLastSeenDesc(p.getName()).stream()
            .anyMatch(c -> !c.getId().equals(p.getId()) && c.hasPass() && encoder.matches(pw, c.getPassHash()));
        if (taken) throw new ResponseStatusException(HttpStatus.CONFLICT, "別の合言葉にしてね");
        p.setPassHash(encoder.encode(pw));
        players.save(p);
    }

    // ---------- 管理人室から ----------

    public List<Player> listAll() {
        return players.findAllByOrderByLastSeenDesc();
    }

    /** 合言葉を忘れた人のために、合言葉を消す（次にそのなまえで入った人が新しい合言葉を決められる） */
    @Transactional
    public void resetPass(Long id) {
        players.findById(id).ifPresent(p -> { p.setPassHash(null); players.save(p); });
    }

    // ---------- 道具 ----------

    private static String checkPass(String pass) {
        String pw = pass == null ? "" : pass.strip();
        int len = pw.codePointCount(0, pw.length());
        if (len < 4 || len > 32) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合言葉は4〜32文字で決めてね");
        return pw;
    }

    private String newToken() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static String cleanName(String name) {
        String n = name == null ? "" : name.strip().replaceAll("[\\p{Cntrl}<>]", "");
        if (n.isEmpty()) n = "たびびと";
        return n.codePointCount(0, n.length()) > 8 ? n.substring(0, n.offsetByCodePoints(0, 8)) : n;
    }

    private static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
