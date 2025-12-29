package de.bytephil.guessthesong.songs;

import java.util.List;

public class SongCategory {

    private String id;
    private String label;
    private List<SongItem> songs;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<SongItem> getSongs() {
        return songs;
    }

    public void setSongs(List<SongItem> songs) {
        this.songs = songs;
    }
}
