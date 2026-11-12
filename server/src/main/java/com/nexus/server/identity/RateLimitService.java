package com.nexus.server.identity;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
public final class RateLimitService {

    private final RateLimitStore store;

    public RateLimitService(RateLimitStore store) {
        this.store = store;
    }

    public void consume(String scope, String subject, long limit, Duration window) {
        String key = "chat:rate:" + digest(scope + ":" + subject);
        if (store.increment(key, window) > limit) {
            throw new TooManyRequestsException(window.toSeconds());
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }
}
