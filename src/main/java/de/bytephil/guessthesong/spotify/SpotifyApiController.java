package de.bytephil.guessthesong.spotify;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.core.env.Environment;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.player.GetUsersCurrentlyPlayingTrackRequest;

import jakarta.servlet.http.HttpSession;

@RestController
public class SpotifyApiController {

    private final SpotifyService spotifyService;
    private final SpotifyProperties spotifyProperties;
    private final Environment environment;

    public SpotifyApiController(SpotifyService spotifyService, SpotifyProperties spotifyProperties, Environment environment) {
        this.spotifyService = spotifyService;
        this.spotifyProperties = spotifyProperties;
        this.environment = environment;
    }

    @GetMapping("/spotify/status")
    public Object status(HttpSession session) {
        boolean globalAuthed = spotifyService.getGlobalToken() != null;
        return java.util.Map.of(
            "authenticated", spotifyService.getToken(session) != null,
            "globalAuthenticated", globalAuthed);
    }

    @GetMapping("/spotify/config-check")
    public Object configCheck() {
        String[] profiles = environment.getActiveProfiles();

        return java.util.Map.of(
                "activeProfiles", profiles,
                "clientIdPresent", spotifyProperties.getClientId() != null && !spotifyProperties.getClientId().isBlank(),
                "clientSecretPresent", spotifyProperties.getClientSecret() != null && !spotifyProperties.getClientSecret().isBlank(),
            "globalRefreshTokenPresent", spotifyProperties.getGlobalRefreshToken() != null
                && !spotifyProperties.getGlobalRefreshToken().isBlank(),
                "redirectUri", spotifyProperties.getRedirectUri(),
                "scopes", spotifyProperties.getScopes());
    }

    @GetMapping("/spotify/current")
    public ResponseEntity<?> current(HttpSession session) throws Exception {
        SpotifyApi api = spotifyService.apiForGlobal();
        if (api == null) {
            api = spotifyService.apiForSession(session);
        }
        if (api == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("error", "not_authenticated"));
        }

        GetUsersCurrentlyPlayingTrackRequest request = api.getUsersCurrentlyPlayingTrack().build();
        var currentlyPlaying = request.execute();
        if (currentlyPlaying == null || currentlyPlaying.getItem() == null) {
            return ResponseEntity.ok(java.util.Map.of("playing", false));
        }

        Track track = (Track) currentlyPlaying.getItem();

        String artistsText = "";
        if (track.getArtists() != null) {
            artistsText = java.util.Arrays.stream(track.getArtists())
                    .map(ArtistSimplified::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }

        String albumImageUrl = null;
        if (track.getAlbum() != null && track.getAlbum().getImages() != null) {
            Image[] images = track.getAlbum().getImages();
            if (images.length > 0) {
                albumImageUrl = images[0].getUrl();
            }
        }

        return ResponseEntity.ok(java.util.Map.of(
                "type", "answer",
                "songTitle", track.getName(),
            "artistsText", artistsText,
                "albumImageUrl", albumImageUrl));
    }
}
