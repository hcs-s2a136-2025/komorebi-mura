package jp.komorebi.ws; public interface MessageHandler { String type(); void handle(PlayerSession from, com.fasterxml.jackson.databind.JsonNode msg, GameSocketHandler hub) throws Exception; }
