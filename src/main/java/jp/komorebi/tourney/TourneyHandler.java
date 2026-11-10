package jp.komorebi.tourney;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jp.komorebi.ws.GameSocketHandler;
import jp.komorebi.ws.MessageHandler;
import jp.komorebi.ws.PlayerSession;

/** 村の大会（パズル大会・釣り大会）。約束は TourneyService を見る */
@Component
public class TourneyHandler implements MessageHandler {

    private final TourneyService tourney;

    public TourneyHandler(TourneyService tourney) {
        this.tourney = tourney;
    }

    @Override
    public String type() {
        return "tny";
    }

    @Override
    public void handle(PlayerSession from, JsonNode msg, GameSocketHandler hub) {
        tourney.handle(from, msg, hub);
    }
}
