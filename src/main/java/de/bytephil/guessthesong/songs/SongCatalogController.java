package de.bytephil.guessthesong.songs;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SongCatalogController {

    private final SongCatalogService songCatalogService;

    public SongCatalogController(SongCatalogService songCatalogService) {
        this.songCatalogService = songCatalogService;
    }

    @GetMapping("/songs/catalog")
    public SongCatalog catalog() {
        return songCatalogService.getCatalog();
    }
}
