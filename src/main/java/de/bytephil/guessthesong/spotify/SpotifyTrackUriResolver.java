package de.bytephil.guessthesong.spotify;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Track;

@Service
public class SpotifyTrackUriResolver {

    private static final long DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L;

    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public String resolveTrackUri(SpotifyApi api, String query) throws Exception {
        if (api == null) {
            return null;
        }

        String normalized = normalizeKey(query);
        if (normalized == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(normalized);
        if (cached != null && cached.expiresAtEpochMs > now) {
            return cached.uri;
        }

        var searchResult = api.searchTracks(query)
                .limit(1)
                .build()
                .execute();

        Track[] foundTracks = searchResult != null ? searchResult.getItems() : null;
        Track track = (foundTracks != null && foundTracks.length > 0) ? foundTracks[0] : null;
        String uri = track != null ? track.getUri() : null;

        // cache negative results too, but shorter
        long ttlMs = (uri != null) ? DEFAULT_TTL_MS : 5L * 60L * 1000L;
        cache.put(normalized, new CacheEntry(uri, now + ttlMs));
        return uri;
    }

    private static String normalizeKey(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceAll("\\s+", " ").toLowerCase();
    }

    private record CacheEntry(String uri, long expiresAtEpochMs) {
    }
}
