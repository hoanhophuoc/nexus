package com.haichutieu.chatsystem.server.messaging;

import com.haichutieu.chatsystem.server.identity.AuthenticationService;
import com.haichutieu.chatsystem.server.identity.SessionAuthenticationFilter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Component
public class SessionHandshakeInterceptor implements HandshakeInterceptor {
    static final String USER_ID = "authenticatedUserId";
    static final String SESSION_TOKEN = "authenticatedSessionToken";
    private final AuthenticationService authentication;

    public SessionHandshakeInterceptor(AuthenticationService authentication) { this.authentication = authentication; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String cookieHeader = request.getHeaders().getFirst("Cookie");
        if (cookieHeader == null) return false;
        for (String cookie : cookieHeader.split(";")) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && SessionAuthenticationFilter.COOKIE_NAME.equals(pair[0])) {
                UUID userId = authentication.authenticate(pair[1]).map(identity -> identity.userId()).orElse(null);
                if (userId != null) {
                    attributes.put(USER_ID, userId);
                    attributes.put(SESSION_TOKEN, pair[1]);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) { }
}
