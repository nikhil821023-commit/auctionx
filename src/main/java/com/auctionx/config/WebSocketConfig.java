package com.auctionx.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Server → Client broadcasts go through these topic prefixes
        registry.enableSimpleBroker(
                "/topic/lobby",    // lobby updates (teams joining, ready status)
                "/topic/auction",  // Phase 3 - auction events
                "/topic/dashboard" // Phase 3 - live dashboard
        );
        // Client → Server messages use this prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS(); // fallback for browsers without native WS
    }
}