package de.bytephil.guessthesong.spotify;

import java.net.URI;
import java.time.Instant;

import org.springframework.stereotype.Service;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;

import jakarta.servlet.http.HttpSession;

@Service
public class SpotifyService {

    public static final String SESSION_TOKEN_KEY = "SPOTIFY_TOKEN";
    public static final String SESSION_STATE_KEY = "SPOTIFY_OAUTH_STATE";

    private final SpotifyProperties properties;

    public SpotifyService(SpotifyProperties properties) {
        this.properties = properties;
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
}
