package de.bytephil.guessthesong.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        // Ensure an ObjectMapper bean exists even if auto-configuration gets skipped.
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }
}
