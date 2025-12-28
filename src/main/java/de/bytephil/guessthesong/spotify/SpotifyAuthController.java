package de.bytephil.guessthesong.spotify;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.http.ResponseCookie;

import se.michaelthelin.spotify.SpotifyApi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Controller
public class SpotifyAuthController {

    private static final Logger logger = LoggerFactory.getLogger(SpotifyAuthController.class);

    private final SpotifyService spotifyService;
    private final SpotifyProperties properties;

    public SpotifyAuthController(SpotifyService spotifyService, SpotifyProperties properties) {
        this.spotifyService = spotifyService;
        this.properties = properties;
    }

    @GetMapping("/spotify/login")
    public RedirectView login(HttpSession session, HttpServletResponse response) throws Exception {
        if (isBlank(properties.getClientId()) || isBlank(properties.getClientSecret()) || isBlank(properties.getRedirectUri())) {
            return new RedirectView("/?spotifyError=missing_spotify_config");
        }

        String state = generateState();
        session.setAttribute(SpotifyService.SESSION_STATE_KEY, state);
        setStateCookie(response, state);

        SpotifyApi api = spotifyService.newBaseApi();
        String scopes = properties.getScopes();

        String authUrl = api.authorizationCodeUri()
                .state(state)
                .scope(scopes)
                .show_dialog(true)
                .build()
                .execute()
                .toString();

        return new RedirectView(authUrl);
    }

    @GetMapping("/spotify/callback")
    public RedirectView callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session) throws Exception {

        if (error != null && !error.isBlank()) {
            return new RedirectView("/?spotifyError=" + urlEncode(error));
        }

        String expectedStateSession = (String) session.getAttribute(SpotifyService.SESSION_STATE_KEY);
        session.removeAttribute(SpotifyService.SESSION_STATE_KEY);
        String expectedStateCookie = readStateCookie(request);
        clearStateCookie(response);

        boolean stateOk = false;
        if (state != null) {
            if (expectedStateSession != null && expectedStateSession.equals(state)) {
                stateOk = true;
            }
            if (!stateOk && expectedStateCookie != null && expectedStateCookie.equals(state)) {
                stateOk = true;
            }
        }

        if (!stateOk) {
            return new RedirectView("/?spotifyError=invalid_state");
        }

        if (code == null || code.isBlank()) {
            return new RedirectView("/?spotifyError=missing_code");
        }

        SpotifySessionToken token = spotifyService.exchangeCodeForToken(code);
        spotifyService.setToken(session, token);

        if (properties.isPauseOnConnect()) {
            try {
                SpotifyApi api = spotifyService.apiForSession(session);
                if (api != null) {
                    api.pauseUsersPlayback().build().execute();
                }
            } catch (Exception e) {
                logger.info("Spotify pause-on-connect failed (often no active device): {}", e.getMessage());
            }
        }

        return new RedirectView("/?spotify=connected");
    }

    @GetMapping("/spotify/logout")
    public RedirectView logout(HttpSession session) {
        spotifyService.clearToken(session);
        return new RedirectView("/?spotify=logged_out");
    }

    private static String generateState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static final String STATE_COOKIE_NAME = "spotify_oauth_state";

    private static void setStateCookie(HttpServletResponse response, String state) {
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE_NAME, state)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/spotify")
                .maxAge(Duration.ofMinutes(5))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private static void clearStateCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/spotify")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private static String readStateCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (STATE_COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
