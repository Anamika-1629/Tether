package com.tether.sync.config;

import com.tether.sync.websocket.SyncWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SyncWebSocketHandler syncWebSocketHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            SyncWebSocketHandler syncWebSocketHandler,
            @Value("${tether.cors.allowed-origins:http://localhost:3000,http://localhost:5173}") String origins) {
        this.syncWebSocketHandler = syncWebSocketHandler;
        this.allowedOrigins = origins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(syncWebSocketHandler, "/sync/*")
                .setAllowedOrigins(allowedOrigins);
    }
}
