package de.bytephil.guessthesong.spotify;

import java.net.URI;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;

import jakarta.servlet.http.HttpSession;

@Service
public class SpotifyService {

    private static final Logger logger = LoggerFactory.getLogger(SpotifyService.class);

    public static final String SESSION_TOKEN_KEY = "SPOTIFY_TOKEN";
    public static final String SESSION_STATE_KEY = "SPOTIFY_OAUTH_STATE";

    private final SpotifyProperties properties;

    // In-memory token shared across the whole backend process.
    // This makes Spotify control independent of any particular browser/HTTP session.
    // Note: This is not persisted; restarting the server loses the token.
    private volatile SpotifySessionToken globalToken;

    public SpotifyService(SpotifyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initGlobalTokenFromConfig() {
        String refreshToken = properties.getGlobalRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        // Mark as expired so refreshGlobal() will fetch a fresh access token.
        globalToken = new SpotifySessionToken(null, refreshToken.trim(), 0);
        try {
            refreshGlobal();
            logger.info("Initialized global Spotify token from config refresh token");
        } catch (Exception e) {
            globalToken = null;
            logger.warn("Failed to initialize global Spotify token from config (global token disabled)", e);
        }
    }

    public SpotifyApi newBaseApi() {
        return new SpotifyApi.Builder()
                .setClientId(properties.getClientId())
                .setClientSecret(properties.getClientSecret())
                .setRedirectUri(URI.create(properties.getRedirectUri()))
                .build();
    }

    public SpotifySessionToken getToken(HttpSession session) {
        Object obj = session.getAttribute(SESSION_TOKEN_KEY);
        if (obj instanceof SpotifySessionToken token) {
            return token;
        }
        return null;
    }

    public void setToken(HttpSession session, SpotifySessionToken token) {
        session.setAttribute(SESSION_TOKEN_KEY, token);
    }

    public void clearToken(HttpSession session) {
        session.removeAttribute(SESSION_TOKEN_KEY);
    }

    public SpotifyApi apiForSession(HttpSession session) throws SpotifyWebApiException {
        SpotifySessionToken token = getToken(session);
        if (token == null) {
            return null;
        }

        long now = Instant.now().toEpochMilli();
        // refresh 60s before expiry
        if (token.getRefreshToken() != null && token.isExpiredOrNearExpiry(now, 60_000)) {
            refresh(session);
            token = getToken(session);
        }

        return new SpotifyApi.Builder()
                .setClientId(properties.getClientId())
                .setClientSecret(properties.getClientSecret())
                .setRedirectUri(URI.create(properties.getRedirectUri()))
                .setAccessToken(token.getAccessToken())
                .setRefreshToken(token.getRefreshToken())
                .build();
    }

    public SpotifySessionToken getGlobalToken() {
        return globalToken;
    }

    public void setGlobalToken(SpotifySessionToken token) {
        this.globalToken = token;
    }

    public void clearGlobalToken() {
        this.globalToken = null;
    }

    public SpotifyApi apiForGlobal() throws SpotifyWebApiException {
        SpotifySessionToken token = globalToken;
        if (token == null) {
            return null;
        }

        long now = Instant.now().toEpochMilli();
        // refresh 60s before expiry
        if (token.getRefreshToken() != null && token.isExpiredOrNearExpiry(now, 60_000)) {
            refreshGlobal();
            token = globalToken;
        }

        if (token == null) {
            return null;
        }

        return new SpotifyApi.Builder()
                .setClientId(properties.getClientId())
                .setClientSecret(properties.getClientSecret())
                .setRedirectUri(URI.create(properties.getRedirectUri()))
                .setAccessToken(token.getAccessToken())
                .setRefreshToken(token.getRefreshToken())
                .build();
    }

    public SpotifySessionToken exchangeCodeForToken(String code) throws Exception {
        SpotifyApi api = newBaseApi();
        AuthorizationCodeCredentials creds = api.authorizationCode(code).build().execute();

        long expiresAt = Instant.now().toEpochMilli() + (creds.getExpiresIn() * 1000L);
        return new SpotifySessionToken(creds.getAccessToken(), creds.getRefreshToken(), expiresAt);
    }

    public void refresh(HttpSession session) throws SpotifyWebApiException {
        SpotifySessionToken token = getToken(session);
        if (token == null || token.getRefreshToken() == null) {
            return;
        }

        try {
            SpotifyApi api = new SpotifyApi.Builder()
                    .setClientId(properties.getClientId())
                    .setClientSecret(properties.getClientSecret())
                    .setRedirectUri(URI.create(properties.getRedirectUri()))
                    .setRefreshToken(token.getRefreshToken())
                    .build();

            AuthorizationCodeCredentials refreshed = api.authorizationCodeRefresh().build().execute();
            long expiresAt = Instant.now().toEpochMilli() + (refreshed.getExpiresIn() * 1000L);

            // Spotify may not always return a refresh token on refresh
            token.setAccessToken(refreshed.getAccessToken());
            token.setExpiresAtEpochMs(expiresAt);
            setToken(session, token);
        } catch (Exception e) {
            throw new SpotifyWebApiException("Failed to refresh Spotify token", e);
        }
    }

    public void refreshGlobal() throws SpotifyWebApiException {
        // Only one refresh at a time.
        synchronized (this) {
            SpotifySessionToken token = globalToken;
            if (token == null || token.getRefreshToken() == null) {
                return;
            }

            long now = Instant.now().toEpochMilli();
            if (!token.isExpiredOrNearExpiry(now, 60_000)) {
                return;
            }

            try {
                SpotifyApi api = new SpotifyApi.Builder()
                        .setClientId(properties.getClientId())
                        .setClientSecret(properties.getClientSecret())
                        .setRedirectUri(URI.create(properties.getRedirectUri()))
                        .setRefreshToken(token.getRefreshToken())
                        .build();

                AuthorizationCodeCredentials refreshed = api.authorizationCodeRefresh().build().execute();
                long expiresAt = Instant.now().toEpochMilli() + (refreshed.getExpiresIn() * 1000L);

                token.setAccessToken(refreshed.getAccessToken());
                token.setExpiresAtEpochMs(expiresAt);
                globalToken = token;
            } catch (Exception e) {
                throw new SpotifyWebApiException("Failed to refresh Spotify token", e);
            }
        }
    }
}
