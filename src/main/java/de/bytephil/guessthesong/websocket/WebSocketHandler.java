package de.bytephil.guessthesong.websocket;

import java.io.IOException;
import java.util.EnumSet;
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

import de.bytephil.guessthesong.spotify.SpotifyService;
import jakarta.servlet.http.HttpSession;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.enums.Action;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.miscellaneous.Device;
import se.michaelthelin.spotify.model_objects.special.Actions;
import se.michaelthelin.spotify.model_objects.specification.Disallows;

@Component
public class WebSocketHandler extends TextWebSocketHandler {

    private String guesserName = null;
    private String lastBroadcast = null;

    private final SpotifyService spotifyService;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CurrentlyPlayingContext safeGetPlayback(SpotifyApi api, String wsId) {
        try {
            return api.getInformationAboutUsersCurrentPlayback().build().execute();
        } catch (Exception e) {
            logger.warn("WS {} -> spotify get playback failed", wsId, e);
            return null;
        }
    }

    private static EnumSet<Action> getDisallowedActions(CurrentlyPlayingContext playback) {
        if (playback == null) {
            return null;
        }
        Actions actions = playback.getActions();
        if (actions == null) {
            return null;
        }
        Disallows disallows = actions.getDisallows();
        if (disallows == null) {
            return null;
        }
        return disallows.getDisallowedActions();
    }

    private boolean canResume(CurrentlyPlayingContext playback) {
        EnumSet<Action> disallowed = getDisallowedActions(playback);
        return disallowed == null || !disallowed.contains(Action.RESUMING);
    }

    private boolean canPause(CurrentlyPlayingContext playback) {
        EnumSet<Action> disallowed = getDisallowedActions(playback);
        return disallowed == null || !disallowed.contains(Action.PAUSING);
    }

    private boolean deviceSupportsVolume(CurrentlyPlayingContext playback) {
        if (playback == null) {
            return false;
        }
        Device device = playback.getDevice();
        return device != null && Boolean.TRUE.equals(device.getSupports_volume());
    }

    private SpotifyApi findSpotifyApiFromAnyConnectedSession() {
        try {
            SpotifyApi global = spotifyService.apiForGlobal();
            if (global != null) {
                logger.info("WS -> Using global Spotify token");
                return global;
            }
        } catch (Exception e) {
            logger.warn("WS -> Failed building global Spotify API", e);
        }

        for (WebSocketSession s : sessions) {
            HttpSession httpSession = (HttpSession) s.getAttributes()
                    .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ATTR);
            if (httpSession == null) {
                continue;
            }

            try {
                SpotifyApi api = spotifyService.apiForSession(httpSession);
                if (api != null) {
                    logger.info("WS -> Using Spotify token from HTTP session {} (ws={})", httpSession.getId(),
                            s.getId());
                    return api;
                }
            } catch (Exception e) {
                logger.warn("WS -> Failed building Spotify API for ws={}", s.getId(), e);
            }
        }
        return null;
    }

    public WebSocketHandler(SpotifyService spotifyService) {
        this.spotifyService = spotifyService;
    }

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

                    // Pause playback using ANY connected session that has a Spotify token (usually
                    // the host/"Phil").
                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (pause skipped)", session.getId());
                    } else {
                        try {
                            CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                            Boolean isPlaying = playback != null ? playback.getIs_playing() : null;
                            if (Boolean.FALSE.equals(isPlaying)) {
                                logger.info("WS {} -> spotify pause skipped (already not playing)", session.getId());
                            } else if (!canPause(playback)) {
                                logger.info("WS {} -> spotify pause skipped (disallowed by Spotify)", session.getId());
                            } else {
                                api.pauseUsersPlayback().build().execute();
                                logger.info("WS {} -> spotify pause executed", session.getId());
                            }
                        } catch (Exception e) {
                            // Often: 404 no active device, 403 missing scope, 402 premium required, etc.
                            logger.warn("WS {} -> spotify pause failed", session.getId(), e);
                        }
                    }

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
                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (resume skipped)", session.getId());
                    } else {
                        try {
                            CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                            Boolean isPlaying = playback != null ? playback.getIs_playing() : null;
                            if (Boolean.TRUE.equals(isPlaying)) {
                                logger.info("WS {} -> spotify resume skipped (already playing)", session.getId());
                            } else if (!canResume(playback)) {
                                logger.info("WS {} -> spotify resume skipped (disallowed by Spotify)", session.getId());
                            } else {
                                api.startResumeUsersPlayback().build().execute();
                                if (deviceSupportsVolume(playback)) {
                                    api.setVolumeForUsersPlayback(75).build().execute();
                                }
                                logger.info("WS {} -> spotify resume executed", session.getId());
                            }
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify resume failed", session.getId(), e);
                        }
                    }

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

                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (resume skipped)", session.getId());
                    } else {
                        try {
                            CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                            Boolean isPlaying = playback != null ? playback.getIs_playing() : null;
                            if (Boolean.TRUE.equals(isPlaying)) {
                                logger.info("WS {} -> spotify resume skipped (already playing)", session.getId());
                                if (deviceSupportsVolume(playback)) {
                                    api.setVolumeForUsersPlayback(100).build().execute();
                                }
                            } else if (!canResume(playback)) {
                                logger.info("WS {} -> spotify resume skipped (disallowed by Spotify)", session.getId());
                            } else {
                                api.startResumeUsersPlayback().build().execute();
                                if (deviceSupportsVolume(playback)) {
                                    api.setVolumeForUsersPlayback(100).build().execute();
                                }
                                logger.info("WS {} -> spotify resume executed", session.getId());
                            }
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify resume failed", session.getId(), e);
                        }
                    }
                } else if ("guessAgain".equals(clientMessage.type)) {
                    // Handle guess again request
                    String guessAgainJSON = objectMapper.writeValueAsString(
                            Map.of("type", "guessAgain"));
                    lastBroadcast = guessAgainJSON;
                    broadcast(guessAgainJSON);
                    guesserName = null;
                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (resume skipped)", session.getId());
                    } else {
                        try {
                            CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                            Boolean isPlaying = playback != null ? playback.getIs_playing() : null;
                            if (Boolean.TRUE.equals(isPlaying)) {
                                logger.info("WS {} -> spotify resume skipped (already playing)", session.getId());
                            } else if (!canResume(playback)) {
                                logger.info("WS {} -> spotify resume skipped (disallowed by Spotify)", session.getId());
                            } else {
                                api.startResumeUsersPlayback().build().execute();
                                if (deviceSupportsVolume(playback)) {
                                    api.setVolumeForUsersPlayback(100).build().execute();
                                }
                                logger.info("WS {} -> spotify resume executed", session.getId());
                            }
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify resume failed", session.getId(), e);
                        }
                    }
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
