package de.bytephil.guessthesong.songs;

import java.util.List;

public class SongCatalog {

    private int version;
    private List<SongCategory> categories;

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<SongCategory> getCategories() {
        return categories;
    }

    public void setCategories(List<SongCategory> categories) {
        this.categories = categories;
    }
}
