package de.bytephil.guessthesong.websocket;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class BasicWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(BasicWebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        session.sendMessage(new TextMessage("connected:" + session.getId()));
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
                logger.info("WS {} -> type={}, playerName={}", session.getId(), clientMessage.type, clientMessage.playerName);
            } catch (Exception e) {
                logger.info("WS {} -> invalid JSON: {}", session.getId(), jsonPayload);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // no-op
    }
}
