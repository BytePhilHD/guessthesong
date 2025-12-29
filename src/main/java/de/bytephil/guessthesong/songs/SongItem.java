package de.bytephil.guessthesong.songs;

import java.util.List;

public class SongItem {

    private String title;
    private List<String> artists;

    // Optional: can be filled later for precise control.
    private String spotifyUri;

    // Optional: alternative to uri; useful when curating manually.
    private String spotifyQuery;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getArtists() {
        return artists;
    }

    public void setArtists(List<String> artists) {
        this.artists = artists;
    }

    public String getSpotifyUri() {
        return spotifyUri;
    }

    public void setSpotifyUri(String spotifyUri) {
        this.spotifyUri = spotifyUri;
    }

    public String getSpotifyQuery() {
        return spotifyQuery;
    }

    public void setSpotifyQuery(String spotifyQuery) {
        this.spotifyQuery = spotifyQuery;
    }
}
