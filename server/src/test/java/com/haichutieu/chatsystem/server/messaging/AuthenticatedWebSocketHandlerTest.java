package com.haichutieu.chatsystem.server.messaging;

import com.haichutieu.chatsystem.server.identity.AuthenticationService;
import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import com.haichutieu.chatsystem.server.social.PresenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedWebSocketHandlerTest {
    private final AuthenticationService authentication = mock(AuthenticationService.class);
    private final PresenceStore presence = mock(PresenceStore.class);
    private final AuthenticatedWebSocketHandler handler =
            new AuthenticatedWebSocketHandler(JsonMapper.builder().build(), authentication, presence,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SessionHandshakeInterceptor.USER_ID, userId);
        attributes.put(SessionHandshakeInterceptor.SESSION_TOKEN, "opaque-token");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("connection-1");
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void tracksPresenceForTheSocketLifetime() {
        verify(presence).online(userId, "connection-1");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(presence).offline(userId, "connection-1");
    }

    @Test
    void forwardsOnlyToTheAuthenticatedTargetAndRechecksTheSession() throws Exception {
        when(authentication.authenticate("opaque-token")).thenReturn(Optional.of(new SessionIdentity(userId, "user")));
        var event = new DefaultMessage("events".getBytes(), ("{\"targets\":[\"" + userId
                + "\"],\"payload\":{\"type\":\"MessageCreated\",\"eventId\":\"event\"}}")
                .getBytes());

        handler.onMessage(event, null);

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void closesConnectionsWhoseSessionWasRevoked() throws Exception {
        when(authentication.authenticate("opaque-token")).thenReturn(Optional.empty());
        var event = new DefaultMessage("events".getBytes(), ("{\"targets\":[\"" + userId
                + "\"],\"payload\":{}} ").getBytes());

        handler.onMessage(event, null);

        verify(session, never()).sendMessage(any(TextMessage.class));
        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("Session revoked."));
    }
}
