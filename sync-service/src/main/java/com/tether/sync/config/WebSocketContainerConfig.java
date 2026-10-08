package com.tether.sync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Tomcat's default maximum WebSocket binary message is 8 KB, and it closes the connection (code 1009)
 * on anything larger. Yjs documents and compaction snapshots routinely exceed that.
 */
@Configuration
public class WebSocketContainerConfig {

    @Bean
    ServletServerContainerFactoryBean webSocketContainer(
            @Value("${tether.sync.max-message-bytes:1048576}") int maxMessageBytes) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(maxMessageBytes);
        container.setMaxTextMessageBufferSize(maxMessageBytes);
        return container;
    }
}
