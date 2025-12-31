package de.bytephil.guessthesong.spotify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spotify")
public class SpotifyProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String scopes;
    private boolean pauseOnConnect;
    /**
     * Optional: a long-lived refresh token to allow the backend to control Spotify
     * without a browser session login.
     */
    private String globalRefreshToken;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public boolean isPauseOnConnect() {
        return pauseOnConnect;
    }

    public void setPauseOnConnect(boolean pauseOnConnect) {
        this.pauseOnConnect = pauseOnConnect;
    }

    public String getGlobalRefreshToken() {
        return globalRefreshToken;
    }

    public void setGlobalRefreshToken(String globalRefreshToken) {
        this.globalRefreshToken = globalRefreshToken;
    }
}
