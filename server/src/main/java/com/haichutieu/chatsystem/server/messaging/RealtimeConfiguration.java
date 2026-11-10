package com.haichutieu.chatsystem.server.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RealtimeConfiguration implements WebSocketConfigurer {
    private final AuthenticatedWebSocketHandler handler;
    private final SessionHandshakeInterceptor handshake;

    public RealtimeConfiguration(AuthenticatedWebSocketHandler handler, SessionHandshakeInterceptor handshake) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/v1").addInterceptors(handshake);
    }

    @Bean
    RedisMessageListenerContainer realtimeListener(RedisConnectionFactory connections,
                                                   AuthenticatedWebSocketHandler handler) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(handler, new ChannelTopic(OutboxPublisher.CHANNEL));
        return container;
    }
}
