package com.haichutieu.chatsystem.server.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    @Test
    void allowsConfiguredRequestsThenRejectsWithoutExposingTheSubject() {
        CountingRateLimitStore store = new CountingRateLimitStore();
        RateLimitService service = new RateLimitService(store);

        service.consume("login", "alice@example.com", 2, Duration.ofMinutes(15));
        service.consume("login", "alice@example.com", 2, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.consume("login", "alice@example.com", 2, Duration.ofMinutes(15)))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageNotContaining("alice@example.com");
        assertThat(store.keys).allMatch(key -> !key.contains("alice@example.com"));
    }

    private static final class CountingRateLimitStore implements RateLimitStore {
        private final Map<String, Long> counts = new HashMap<>();
        private String[] keys = new String[0];

        @Override
        public long increment(String key, Duration expiry) {
            keys = new String[]{key};
            return counts.merge(key, 1L, Long::sum);
        }
    }
}
