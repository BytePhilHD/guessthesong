package de.bytephil.guessthesong.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class BasicWebSocketHandler extends TextWebSocketHandler {

    private String guesserName = null;
    private String lastBroadcast = null;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    private static final Logger logger = LoggerFactory.getLogger(BasicWebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        session.sendMessage(new TextMessage("connected:" + session.getId()));
        if (lastBroadcast != null) {
            broadcast(lastBroadcast);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        final String payload = message.getPayload();
        logger.info("WS {} <- {}", session.getId(), payload);

        // Minimal: echo back what the client sent
        session.sendMessage(new TextMessage("echo:" + payload));

        // Minimal JSON -> Java object parsing
        // Expected: {"type":"...","playerName":"..."}
        String jsonPayload = payload;
        if (jsonPayload != null && jsonPayload.startsWith("answer:")) {
            jsonPayload = jsonPayload.substring("answer:".length());
        }

        if (jsonPayload != null && jsonPayload.trim().startsWith("{")) {
            try {
                ClientMessage clientMessage = objectMapper.readValue(jsonPayload, ClientMessage.class);
                logger.info("WS {} -> type={}, playerName={}", session.getId(), clientMessage.type,
                        clientMessage.playerName);

                if ("playerGuess".equals(clientMessage.type) && guesserName == null) {
                    guesserName = clientMessage.playerName;
                    logger.info("WS {} -> Guesser set to {}", session.getId(), guesserName);

                    // Example: broadcast to everyone
                    String firstGuesserJson = objectMapper.writeValueAsString(
                            Map.of("type", "firstGuesser", "playerName", guesserName));
                    lastBroadcast = firstGuesserJson;
                    broadcast(firstGuesserJson);
                    // TODO Lied stoppen

                } else if ("showAnswer".equals(clientMessage.type)) {
                    // Handle show answer request
                    logger.info("WS {} -> Show answer requested by {}", session.getId(), clientMessage.playerName);
                    // Implement logic to show the answer to all players
                    String answerJson = objectMapper.writeValueAsString(
                            Map.of("type", "answer", "songTitle", "TEST", "artistsText", "TEST ARTIST", "albumImageUrl",
                                    "https://i.scdn.co/image/ab67616d0000b273e9b246fad384459a7b325b3b"));
                    lastBroadcast = answerJson;
                    broadcast(answerJson);
                    guesserName = null;

                } else if ("nextRound".equals(clientMessage.type)) {
                    // Handle next round request
                    logger.info("WS {} -> Next round requested by {}", session.getId(), clientMessage.playerName);
                    // Implement logic to start the next round
                    String nextRoundJson = objectMapper.writeValueAsString(
                            Map.of("type", "nextRound"));
                    lastBroadcast = nextRoundJson;
                    broadcast(nextRoundJson);
                    guesserName = null;
                    // TODO hier muss nächstes Lied geladen werden

                } else if ("guessAgain".equals(clientMessage.type)) {
                    // Handle guess again request
                    String guessAgainJSON = objectMapper.writeValueAsString(
                            Map.of("type", "guessAgain"));
                    lastBroadcast = guessAgainJSON;
                    broadcast(guessAgainJSON);
                    guesserName = null;
                    // TODO Lied wieder starten
                }

            } catch (Exception e) {
                logger.info("WS {} -> invalid JSON: {}", session.getId(), jsonPayload);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        // no-op
    }

    private void broadcast(String payload) {
        TextMessage msg = new TextMessage(payload);
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) {
                sessions.remove(s);
                continue;
            }
            try {
                s.sendMessage(msg);
            } catch (IOException e) {
                logger.info("WS {} -> broadcast failed", s.getId(), e);
            }
        }
    }
}
