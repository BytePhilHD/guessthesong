package de.bytephil.guessthesong.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final BasicWebSocketHandler basicWebSocketHandler;

	public WebSocketConfig(BasicWebSocketHandler basicWebSocketHandler) {
		this.basicWebSocketHandler = basicWebSocketHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(basicWebSocketHandler, "/ws")
				.setAllowedOrigins("*");
	}
}
