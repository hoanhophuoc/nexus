package com.haichutieu.chatsystem.server.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.haichutieu.chatsystem.server.identity.AuthenticationService;
import com.haichutieu.chatsystem.server.social.PresenceStore;
import org.springframework.data.redis.connection.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthenticatedWebSocketHandler extends TextWebSocketHandler implements MessageListener {
    private final ObjectMapper mapper;
    private final AuthenticationService authentication;
    private final PresenceStore presence;
    private final Counter delivered;
    private final Timer sendLatency;
    private final Timer authLatency;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public AuthenticatedWebSocketHandler(ObjectMapper mapper, AuthenticationService authentication,
                                         PresenceStore presence, MeterRegistry meters) {
        this.mapper = mapper;
        this.authentication = authentication;
        this.presence = presence;
        this.delivered = Counter.builder("chat.ws.delivered")
                .description("Realtime events written to an open socket")
                .register(meters);
        this.sendLatency = Timer.builder("chat.ws.send")
                .description("Time from message creation to socket write, as the server sees it")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
        this.authLatency = Timer.builder("chat.ws.auth")
                .description("Session revalidation performed while delivering an event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
        Gauge.builder("chat.ws.sessions", this, handler -> handler.openSessions())
                .description("Open authenticated WebSocket sessions on this instance")
                .register(meters);
    }

    private int openSessions() {
        int open = 0;
        for (Set<WebSocketSession> active : sessions.values()) {
            for (WebSocketSession session : active) {
                if (session.isOpen()) open++;
            }
        }
        return open;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = (UUID) session.getAttributes().get(SessionHandshakeInterceptor.USER_ID);
        if (userId == null) {
            try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (IOException ignored) { }
            return;
        }
        sessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        try {
            presence.online(userId, session.getId());
        } catch (RuntimeException ignored) {
            // Presence is advisory; a Redis outage must not reject an authenticated socket.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = (UUID) session.getAttributes().get(SessionHandshakeInterceptor.USER_ID);
        if (userId == null) return;
        Set<WebSocketSession> active = sessions.get(userId);
        if (active != null) {
            active.remove(session);
            if (active.isEmpty()) sessions.remove(userId, active);
        }
        try {
            presence.offline(userId, session.getId());
        } catch (RuntimeException ignored) {
            // The connection-isolated Redis entry expires on its own.
        }
    }

    @Scheduled(fixedDelayString = "${chat.presence.heartbeat-ms:30000}")
    public void refreshPresence() {
        Map<UUID, java.util.List<String>> heartbeats = new java.util.HashMap<>();
        for (Map.Entry<UUID, Set<WebSocketSession>> entry : sessions.entrySet()) {
            java.util.List<String> open = new java.util.ArrayList<>(entry.getValue().size());
            for (WebSocketSession session : entry.getValue()) {
                if (session.isOpen()) open.add(session.getId());
            }
            if (!open.isEmpty()) heartbeats.put(entry.getKey(), open);
        }
        try {
            presence.refresh(heartbeats);
        } catch (RuntimeException ignored) {
            // Retry on the next heartbeat; do not tear down live sockets.
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        session.close(CloseStatus.NOT_ACCEPTABLE.withReason("The server only sends events."));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode envelope = mapper.readTree(message.getBody());
            String payload = mapper.writeValueAsString(envelope.get("payload"));
            java.time.Instant created = null;
            JsonNode createdNode = envelope.get("payload") == null ? null
                    : envelope.get("payload").get("message") == null ? null
                    : envelope.get("payload").get("message").get("createdAt");
            if (createdNode != null && createdNode.isString()) {
                try {
                    created = java.time.Instant.parse(createdNode.asString());
                } catch (RuntimeException ignored) {
                    // Older payloads may omit or mistype the timestamp; skip the sample.
                }
            }
            for (JsonNode target : envelope.withArray("targets")) {
                Set<WebSocketSession> active = sessions.get(UUID.fromString(target.asText()));
                if (active == null) continue;
                for (WebSocketSession session : active) {
                    long authStarted = System.nanoTime();
                    UUID tokenUser = authentication.authenticate((String) session.getAttributes()
                            .get(SessionHandshakeInterceptor.SESSION_TOKEN)).map(identity -> identity.userId()).orElse(null);
                    authLatency.record(java.time.Duration.ofNanos(System.nanoTime() - authStarted));
                    if (session.isOpen() && target.asText().equals(tokenUser == null ? "" : tokenUser.toString())) {
                        synchronized (session) { session.sendMessage(new TextMessage(payload)); }
                        delivered.increment();
                        if (created != null) {
                            sendLatency.record(java.time.Duration.between(created, java.time.Instant.now()));
                        }
                    } else if (session.isOpen() && tokenUser == null) {
                        session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Session revoked."));
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Malformed Redis events are dropped; the durable message remains available through cursor sync.
        }
    }
}
