package com.haichutieu.chatsystem.server;

import com.haichutieu.chatsystem.server.identity.RedisRateLimitStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRateLimitIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void redisScriptIncrementsAtomicallyAndAppliesExpiry() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
            RedisRateLimitStore store = new RedisRateLimitStore(template);
            String key = "chat:test:" + UUID.randomUUID();

            assertThat(store.increment(key, Duration.ofMinutes(1))).isEqualTo(1);
            assertThat(store.increment(key, Duration.ofMinutes(1))).isEqualTo(2);
            assertThat(template.getExpire(key)).isBetween(1L, 60L);
        } finally {
            connectionFactory.destroy();
        }
    }
}
