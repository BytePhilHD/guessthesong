package de.bytephil.guessthesong.websocket;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.bytephil.guessthesong.spotify.SpotifyService;
import jakarta.servlet.http.HttpSession;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.enums.Action;
import se.michaelthelin.spotify.exceptions.detailed.TooManyRequestsException;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.miscellaneous.Device;
import se.michaelthelin.spotify.model_objects.special.Actions;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Disallows;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;

@Component
public class WebSocketHandler extends TextWebSocketHandler {

    private String guesserName = null;
    private String lastBroadcast = null;
    private String selectedGenre = null;

    private final SpotifyService spotifyService;

    private final Object spotifyControlLock = new Object();
    private final AtomicLong spotifyRateLimitedUntilMs = new AtomicLong(0);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

        /**
         * Optional: map a genre to a Spotify playlist context.
         * Fill in your playlist IDs (the 22-char id from open.spotify.com/playlist/<id>). Example:
         * spotify:playlist:37i9dQZF1DXcF6B6QPhFDv
         */
        private static final Map<String, String> GENRE_PLAYLIST_CONTEXT_URIS = Map.of(
            "rock", "spotify:playlist:6pNlO5t2Rg7XrldHrsYbA7",
            "pop", "",
            "electronic", "");

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String normalizeGenreKey(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceAll("\\s+", " ").toLowerCase();
    }

    private static String playlistContextUriForGenre(String genreLabel) {
        String key = normalizeGenreKey(genreLabel);
        if (key == null) {
            return null;
        }

        // Accept both label ("Rock") and id-like values ("rock")
        if (key.contains("rock")) {
            key = "rock";
        } else if (key.contains("pop")) {
            key = "pop";
        } else if (key.contains("electronic")) {
            key = "electronic";
        }

        String ctx = GENRE_PLAYLIST_CONTEXT_URIS.get(key);
        if (ctx == null || ctx.isBlank()) {
            return null;
        }
        return ctx.trim();
    }

    private static String normalizeLabel(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private boolean isSpotifyRateLimitedNow() {
        return System.currentTimeMillis() < spotifyRateLimitedUntilMs.get();
    }

    private void markSpotifyRateLimited(TooManyRequestsException e, String wsId, String action) {
        int retryAfterSeconds = e != null ? e.getRetryAfter() : 1;
        long until = System.currentTimeMillis() + Math.max(1, retryAfterSeconds) * 1000L;
        spotifyRateLimitedUntilMs.set(until);
        logger.warn("WS {} -> Spotify rate limited during {} (retryAfter={}s)", wsId, action, retryAfterSeconds);
    }

    private CurrentlyPlayingContext safeGetPlayback(SpotifyApi api, String wsId) {
        try {
            return api.getInformationAboutUsersCurrentPlayback().build().execute();
        } catch (TooManyRequestsException e) {
            markSpotifyRateLimited(e, wsId, "getPlayback");
            return null;
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

    private static String artistsToText(ArtistSimplified[] artists) {
        if (artists == null || artists.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ArtistSimplified a : artists) {
            if (a == null || a.getName() == null || a.getName().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a.getName());
        }
        return sb.toString();
    }

    private static String firstAlbumImageUrl(Track track) {
        if (track == null || track.getAlbum() == null) {
            return null;
        }
        Image[] images = track.getAlbum().getImages();
        if (images == null || images.length == 0 || images[0] == null) {
            return null;
        }
        return images[0].getUrl();
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

        boolean spotifyConnected = spotifyService.getGlobalToken() != null;

        // Always send current state (client can ignore unknown type)
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "state");
        state.put("spotifyConnected", spotifyConnected);
        if (selectedGenre != null) {
            state.put("genreName", selectedGenre);
        }
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(state)));

        if (lastBroadcast != null) {
            String trimmed = lastBroadcast.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> lastObj = objectMapper.readValue(trimmed, Map.class);
                    // enrich last broadcast with current state
                    lastObj.put("spotifyConnected", spotifyConnected);
                    if (selectedGenre != null) {
                        lastObj.put("genreName", selectedGenre);
                    }
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(lastObj)));
                } catch (Exception e) {
                    // fall back to raw string
                    session.sendMessage(new TextMessage(lastBroadcast));
                }
            } else {
                session.sendMessage(new TextMessage(lastBroadcast));
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        final String payload = message.getPayload();
        logger.info("WS {} <- {}", session.getId(), payload);
        session.sendMessage(new TextMessage("echo:" + payload));

        String jsonPayload = payload;
        if (jsonPayload != null && jsonPayload.startsWith("answer:")) {
            jsonPayload = jsonPayload.substring("answer:".length());
        }

        if (jsonPayload != null && jsonPayload.trim().startsWith("{")) {
            ClientMessage clientMessage;
            try {
                clientMessage = objectMapper.readValue(jsonPayload, ClientMessage.class);
            } catch (JsonProcessingException e) {
                logger.info("WS {} -> invalid JSON: {}", session.getId(), jsonPayload, e);
                return;
            }

            try {
                logger.info("WS {} -> type={}, playerName={}", session.getId(), clientMessage.type,
                        clientMessage.playerName);

                if ("newGame".equals(clientMessage.type)) {
                    selectedGenre = normalizeLabel(clientMessage.genreName);
                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    guesserName = null;

                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (newGame playback skipped)",
                                session.getId());
                    } else if (isSpotifyRateLimitedNow()) {
                        logger.info("WS {} -> spotify newGame skipped (rate limited)", session.getId());
                    } else {
                        try {
                            synchronized (spotifyControlLock) {
                                if (isSpotifyRateLimitedNow()) {
                                    logger.info("WS {} -> spotify newGame skipped (rate limited)", session.getId());
                                    return;
                                }

                                String playlistCtx = playlistContextUriForGenre(selectedGenre);
                                if (playlistCtx == null) {
                                    logger.info("WS {} -> No playlist configured for genre='{}' (newGame skipped)",
                                            session.getId(), selectedGenre);
                                } else {
                                    // Playlist-only mode: avoid search entirely.
                                    api.toggleShuffleForUsersPlayback(true).build().execute();
                                    api.startResumeUsersPlayback().context_uri(playlistCtx).build().execute();
                                    api.skipUsersPlaybackToNextTrack().build().execute();
                                    logger.info("WS {} -> spotify newGame using playlist context {}",
                                            session.getId(), playlistCtx);
                                }
                            }
                        } catch (TooManyRequestsException e) {
                            markSpotifyRateLimited(e, session.getId(), "newGame");
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify newGame failed", session.getId(), e);
                        }
                    }

                } else if ("genreChange".equals(clientMessage.type)) {
                    selectedGenre = normalizeLabel(clientMessage.genreName);
                    logger.info("WS {} -> Genre selected: {}", session.getId(), selectedGenre);

                    String genreChangeJson = objectMapper.writeValueAsString(
                            Map.of("type", "genreChange", "genreName", selectedGenre != null ? selectedGenre : ""));
                    lastBroadcast = genreChangeJson;
                    broadcast(genreChangeJson);
                } else if ("playerGuess".equals(clientMessage.type) && guesserName == null) {
                    guesserName = clientMessage.playerName;
                    logger.info("WS {} -> Guesser set to {}", session.getId(), guesserName);

                    String firstGuesserJson = objectMapper.writeValueAsString(
                            Map.of("type", "firstGuesser", "playerName", guesserName));
                    lastBroadcast = firstGuesserJson;
                    broadcast(firstGuesserJson);

                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (pause skipped)", session.getId());
                    } else if (isSpotifyRateLimitedNow()) {
                        logger.info("WS {} -> spotify pause skipped (rate limited)", session.getId());
                    } else {
                        try {
                            synchronized (spotifyControlLock) {
                                if (isSpotifyRateLimitedNow()) {
                                    logger.info("WS {} -> spotify pause skipped (rate limited)", session.getId());
                                } else {
                                    CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                                    Boolean isPlaying = playback != null ? playback.getIs_playing() : null;
                                    if (Boolean.FALSE.equals(isPlaying)) {
                                        logger.info("WS {} -> spotify pause skipped (already not playing)",
                                                session.getId());
                                    } else if (!canPause(playback)) {
                                        logger.info("WS {} -> spotify pause skipped (disallowed by Spotify)",
                                                session.getId());
                                    } else {
                                        api.pauseUsersPlayback().build().execute();
                                        logger.info("WS {} -> spotify pause executed", session.getId());
                                    }
                                }
                            }
                        } catch (TooManyRequestsException e) {
                            markSpotifyRateLimited(e, session.getId(), "pause");
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify pause failed", session.getId(), e);
                        }
                    }

                } else if ("showAnswer".equals(clientMessage.type)) {
                    // Handle show answer request
                    logger.info("WS {} -> Show answer requested by {}", session.getId(), clientMessage.playerName);

                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (resume skipped)", session.getId());
                    } else if (isSpotifyRateLimitedNow()) {
                        logger.info("WS {} -> spotify resume skipped (rate limited)", session.getId());
                    } else {
                        try {
                            synchronized (spotifyControlLock) {
                                if (isSpotifyRateLimitedNow()) {
                                    logger.info("WS {} -> spotify resume skipped (rate limited)", session.getId());
                                    return;
                                }

                                CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                                Boolean isPlaying = playback != null ? playback.getIs_playing() : null;

                                CurrentlyPlaying current = api.getUsersCurrentlyPlayingTrack().build().execute();
                                Track track = null;
                                if (current != null && current.getItem() instanceof Track t) {
                                    track = t;
                                }

                                String songTitle = track != null ? track.getName() : null;
                                String artistsText = track != null ? artistsToText(track.getArtists()) : "";
                                String albumImageUrl = firstAlbumImageUrl(track);

                                String answerJson = objectMapper.writeValueAsString(
                                        Map.of(
                                                "type", "answer",
                                                "songTitle", songTitle != null ? songTitle : "",
                                                "artistsText", artistsText,
                                                "albumImageUrl", albumImageUrl != null ? albumImageUrl : ""));
                                lastBroadcast = answerJson;
                                broadcast(answerJson);
                                guesserName = null;

                                if (Boolean.TRUE.equals(isPlaying)) {
                                    logger.info("WS {} -> spotify resume skipped (already playing)", session.getId());
                                } else if (!canResume(playback)) {
                                    logger.info("WS {} -> spotify resume skipped (disallowed by Spotify)",
                                            session.getId());
                                } else {
                                    api.startResumeUsersPlayback().build().execute();
                                    if (deviceSupportsVolume(playback)) {
                                        api.setVolumeForUsersPlayback(85).build().execute();
                                    }
                                    logger.info("WS {} -> spotify resume executed", session.getId());
                                }
                            }
                        } catch (TooManyRequestsException e) {
                            markSpotifyRateLimited(e, session.getId(), "showAnswer/resume");
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

                    SpotifyApi api = findSpotifyApiFromAnyConnectedSession();
                    if (api == null) {
                        logger.info("WS {} -> No Spotify session connected (resume skipped)", session.getId());
                    } else if (isSpotifyRateLimitedNow()) {
                        logger.info("WS {} -> spotify nextRound skipped (rate limited)", session.getId());
                    } else {
                        try {
                            synchronized (spotifyControlLock) {
                                if (isSpotifyRateLimitedNow()) {
                                    logger.info("WS {} -> spotify nextRound skipped (rate limited)", session.getId());
                                    return;
                                }

                                CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());

                                // Playlist-only mode: just skip. (Shuffle is enabled at newGame.)
                                api.skipUsersPlaybackToNextTrack().build().execute();

                                if (deviceSupportsVolume(playback)) {
                                    api.setVolumeForUsersPlayback(100).build().execute();
                                }
                            }
                        } catch (TooManyRequestsException e) {
                            markSpotifyRateLimited(e, session.getId(), "nextRound");
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify nextRound failed", session.getId(), e);
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
                    } else if (isSpotifyRateLimitedNow()) {
                        logger.info("WS {} -> spotify resume skipped (rate limited)", session.getId());
                    } else {
                        try {
                            synchronized (spotifyControlLock) {
                                if (isSpotifyRateLimitedNow()) {
                                    logger.info("WS {} -> spotify resume skipped (rate limited)", session.getId());
                                    return;
                                }

                                CurrentlyPlayingContext playback = safeGetPlayback(api, session.getId());
                                Boolean isPlaying = playback != null ? playback.getIs_playing() : null;
                                if (Boolean.TRUE.equals(isPlaying)) {
                                    if (deviceSupportsVolume(playback)) {
                                        api.setVolumeForUsersPlayback(100).build().execute();
                                    }
                                    logger.info("WS {} -> spotify resume skipped (already playing)", session.getId());
                                } else if (!canResume(playback)) {
                                    logger.info("WS {} -> spotify resume skipped (disallowed by Spotify)",
                                            session.getId());
                                } else {
                                    api.startResumeUsersPlayback().build().execute();
                                    if (deviceSupportsVolume(playback)) {
                                        api.setVolumeForUsersPlayback(100).build().execute();
                                    }
                                    logger.info("WS {} -> spotify resume executed", session.getId());
                                }
                            }
                        } catch (TooManyRequestsException e) {
                            markSpotifyRateLimited(e, session.getId(), "guessAgain/resume");
                        } catch (Exception e) {
                            logger.warn("WS {} -> spotify resume failed", session.getId(), e);
                        }
                    }
                }

            } catch (Exception e) {
                logger.warn("WS {} -> message handling failed: {}", session.getId(), jsonPayload, e);
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
