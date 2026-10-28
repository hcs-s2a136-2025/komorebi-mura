package jp.komorebi.job;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/**
 * 村のアルバイトの WebSocket メッセージ（type は "job"）。
 * {"type":"job","a":"letter","to":12,"k":2,"rid":"x"} … 郵便配達：12番の人の家にお手紙（文面2番）を届ける。{"type":"job","a":"letter","rid","ok","text"} が返る
 * {"type":"job","a":"help","on":true} … 労働施設の中の人が「助けて」を出す（false でひっこめる）。村のみんなに {"ev":{"kind":"help",...}}
 * {"type":"job","a":"list"} … 助けを求めている人の一覧 → {"type":"job","a":"list","list":[{id,name,busy}]}
 * {"type":"job","a":"join","id":12} … 12番の人を助けに行く → {"type":"job","a":"joined","ok",...}。相手に {"ev":{"kind":"joined",...}}
 * {"type":"job","a":"rs","id":12,"op":"flash","c":2,"n":3} … 2人の合図（flash・hit・miss・next・ready・lost・bye）。助ける人が "won" を送ると、中の人に {"ev":{"kind":"freed"}}
 */
@Component
public class JobHandler implements MessageHandler {

    private final JobService jobs;

    public JobHandler(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String type() {
        return "job";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        if (from.playerId() == null || from.watcher()) return;
        switch (msg.path("a").asText("")) {
            case "letter" -> {
                String err = jobs.letter(hub, from, msg.path("to").asLong(0), msg.path("k").asInt(-1));
                var out = hub.json().createObjectNode().put("type", "job").put("a", "letter").put("rid", msg.path("rid").asText(""));
                hub.send(from, err == null ? out.put("ok", true) : out.put("ok", false).put("text", err));
            }
            case "help" -> jobs.help(hub, from, msg.path("on").asBoolean(false));
            case "list" -> { if (!jobs.slow("ls" + from.playerId(), 500)) jobs.list(hub, from); }
            case "join" -> { if (!jobs.slow("jn" + from.playerId(), 800)) jobs.join(hub, from, msg.path("id").asLong(0)); }
            case "rs" -> { if (jobs.rsOk(from.playerId())) jobs.relay(hub, from, msg.path("id").asLong(0), msg.path("op").asText(""), msg.path("c").asInt(0), msg.path("n").asInt(0)); }
            default -> { }
        }
    }
}
