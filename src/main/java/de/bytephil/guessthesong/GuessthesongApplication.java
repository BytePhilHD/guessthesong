package de.bytephil.guessthesong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GuessthesongApplication {

	public static void main(String[] args) {
		SpringApplication.run(GuessthesongApplication.class, args);
	}

}
