package de.bytephil.guessthesong.songs;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class SongCatalogService {

    private static final String CATALOG_RESOURCE_PATH = "songs/catalog.json";

    private final ObjectMapper objectMapper;
    private final AtomicReference<SongCatalog> cached = new AtomicReference<>();

    public SongCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SongCatalog getCatalog() {
        SongCatalog existing = cached.get();
        if (existing != null) {
            return existing;
        }

        synchronized (cached) {
            existing = cached.get();
            if (existing != null) {
                return existing;
            }

            SongCatalog loaded = loadCatalog();
            cached.set(loaded);
            return loaded;
        }
    }

    private SongCatalog loadCatalog() {
        try {
            ClassPathResource res = new ClassPathResource(CATALOG_RESOURCE_PATH);
            try (InputStream in = res.getInputStream()) {
                return objectMapper.readValue(in, SongCatalog.class);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load song catalog from classpath:" + CATALOG_RESOURCE_PATH, e);
        }
    }
}
