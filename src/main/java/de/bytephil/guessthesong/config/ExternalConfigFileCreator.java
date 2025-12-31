package de.bytephil.guessthesong.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fallback creator for the external config file.
 *
 * This runs when the Spring context starts up and ensures the file exists.
 * (If it was created on this run, a restart is needed for the new values to be used.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExternalConfigFileCreator implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        ExternalConfigFileEnvironmentPostProcessor.createExternalConfigIfMissing();
    }
}
