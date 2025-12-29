package de.bytephil.guessthesong.songs;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class SongRotationService {

    private final SongCatalogService catalogService;
    private final SecureRandom random = new SecureRandom();

    private final Object lock = new Object();
    private String currentGenreLabel;
    private List<SongItem> playlist = List.of();
    private int nextIndex = 0;

    public SongRotationService(SongCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Returns all songs grouped by the category label (the value used by the frontend).
     */
    public Map<String, List<SongItem>> getAllSongsByGenreLabel() {
        SongCatalog catalog = catalogService.getCatalog();
        List<SongCategory> categories = catalog != null ? catalog.getCategories() : null;
        if (categories == null || categories.isEmpty()) {
            return Map.of();
        }

        Map<String, List<SongItem>> result = new LinkedHashMap<>();
        for (SongCategory c : categories) {
            if (c == null) {
                continue;
            }
            String label = normalize(c.getLabel());
            if (label == null) {
                continue;
            }
            List<SongItem> songs = c.getSongs();
            if (songs == null || songs.isEmpty()) {
                result.put(label, List.of());
            } else {
                List<SongItem> cleaned = songs.stream().filter(Objects::nonNull).toList();
                result.put(label, Collections.unmodifiableList(cleaned));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public List<SongItem> getSongsForGenreLabel(String genreLabel) {
        String needle = normalize(genreLabel);
        if (needle == null) {
            return List.of();
        }

        for (Map.Entry<String, List<SongItem>> e : getAllSongsByGenreLabel().entrySet()) {
            if (normalize(e.getKey()).equalsIgnoreCase(needle)) {
                return e.getValue();
            }
        }

        return List.of();
    }

    /**
     * Resets the rotation for the given genre (shuffle + start at 0).
     */
    public void resetForGenre(String genreLabel) {
        String normalized = normalize(genreLabel);

        synchronized (lock) {
            currentGenreLabel = normalized;
            playlist = new ArrayList<>(getSongsForGenreLabel(normalized));
            Collections.shuffle(playlist, random);
            nextIndex = 0;
        }
    }

    public String getCurrentGenreLabel() {
        synchronized (lock) {
            return currentGenreLabel;
        }
    }

    /**
     * Returns the next song for the current playlist. When the end is reached, reshuffles and starts over.
     */
    public SongItem nextSong() {
        synchronized (lock) {
            if (playlist == null || playlist.isEmpty()) {
                return null;
            }

            if (nextIndex >= playlist.size()) {
                Collections.shuffle(playlist, random);
                nextIndex = 0;
            }

            return playlist.get(nextIndex++);
        }
    }

    public String buildSpotifyQuery(SongItem song) {
        if (song == null) {
            return null;
        }

        String title = normalize(song.getTitle());
        if (title == null) {
            return null;
        }

        String artistsText = normalize(song.getArtistsText());
        if (artistsText == null) {
            return title;
        }

        return title + " - " + artistsText;
    }

    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // keep original case for display labels; only normalize whitespace
        return trimmed.replaceAll("\\s+", " ");
    }
}
