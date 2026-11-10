package com.haichutieu.chatsystem.server.messaging;

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
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public AuthenticatedWebSocketHandler(ObjectMapper mapper, AuthenticationService authentication,
                                         PresenceStore presence) {
        this.mapper = mapper;
        this.authentication = authentication;
        this.presence = presence;
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
        for (Map.Entry<UUID, Set<WebSocketSession>> entry : sessions.entrySet()) {
            for (WebSocketSession session : entry.getValue()) {
                if (!session.isOpen()) continue;
                try {
                    presence.online(entry.getKey(), session.getId());
                } catch (RuntimeException ignored) {
                    // Retry on the next heartbeat; do not tear down a live socket.
                }
            }
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
            for (JsonNode target : envelope.withArray("targets")) {
                Set<WebSocketSession> active = sessions.get(UUID.fromString(target.asText()));
                if (active == null) continue;
                for (WebSocketSession session : active) {
                    UUID tokenUser = authentication.authenticate((String) session.getAttributes()
                            .get(SessionHandshakeInterceptor.SESSION_TOKEN)).map(identity -> identity.userId()).orElse(null);
                    if (session.isOpen() && target.asText().equals(tokenUser == null ? "" : tokenUser.toString())) {
                        synchronized (session) { session.sendMessage(new TextMessage(payload)); }
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
