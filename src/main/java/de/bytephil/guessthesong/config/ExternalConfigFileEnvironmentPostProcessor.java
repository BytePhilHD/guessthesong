package de.bytephil.guessthesong.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Creates an external config file on startup (if missing).
 *
 * Why: resources on the classpath (inside the JAR) are read-only at runtime.
 * An external file is the simplest way to allow local edits (port, secrets, ...).
 */
public class ExternalConfigFileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(ExternalConfigFileEnvironmentPostProcessor.class);

    private static final Path EXTERNAL_CONFIG_PATH = Path.of("config", "application.properties");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, org.springframework.boot.SpringApplication application) {
        createExternalConfigIfMissing();
    }

    @Override
    public int getOrder() {
        // Run as early as possible (before Boot's ConfigData processing).
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static void createExternalConfigIfMissing() {
        try {
            if (Files.exists(EXTERNAL_CONFIG_PATH)) {
                return;
            }

            Files.createDirectories(EXTERNAL_CONFIG_PATH.getParent());

            String template = "# Auto-generated on first start (" + OffsetDateTime.now() + ")\n"
                    + "# Edit values and restart the app.\n"
                    + "\n"
                    + "# Web\n"
                    + "server.port=8080\n"
                    + "\n"
                    + "# Spotify OAuth\n"
                    + "spotify.client-id=\n"
                    + "spotify.client-secret=\n"
                    + "spotify.redirect-uri=http://localhost:${server.port}/spotify/callback\n"
                    + "spotify.scopes=user-read-currently-playing user-modify-playback-state user-read-playback-state\n"
                    + "\n"
                    + "# Optional: backend-only global token (refresh token)\n"
                    + "spotify.global-refresh-token=\n";

            Files.writeString(EXTERNAL_CONFIG_PATH, template, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            logger.warn("Created missing external config file at '{}' (edit it and restart)", EXTERNAL_CONFIG_PATH);
        } catch (IOException e) {
            logger.warn("Failed to create external config file at '{}'", EXTERNAL_CONFIG_PATH, e);
        }
    }
}
