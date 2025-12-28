package de.bytephil.guessthesong.spotify;

import java.io.Serializable;

public class SpotifySessionToken implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accessToken;
    private String refreshToken;
    private long expiresAtEpochMs;

    public SpotifySessionToken() {
    }

    public SpotifySessionToken(String accessToken, String refreshToken, long expiresAtEpochMs) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public long getExpiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public void setExpiresAtEpochMs(long expiresAtEpochMs) {
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    public boolean isExpiredOrNearExpiry(long nowEpochMs, long safetyWindowMs) {
        return expiresAtEpochMs <= (nowEpochMs + safetyWindowMs);
    }
}
