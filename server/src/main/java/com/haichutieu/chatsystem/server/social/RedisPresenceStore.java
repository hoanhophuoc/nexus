package com.haichutieu.chatsystem.server.social;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A user is online while at least one connection heartbeat is fresh. Each connection is a
 * sorted-set member, so disconnecting one client does not hide another server's connection.
 */
@Repository
public class RedisPresenceStore implements PresenceStore {
    static final String KEY_PREFIX = "chat_v1:presence:";

    private final StringRedisTemplate redis;
    private final long ttlMillis;

    public RedisPresenceStore(StringRedisTemplate redis,
                              @Value("${chat.presence.ttl-seconds:90}") long ttlSeconds) {
        if (ttlSeconds < 1) throw new IllegalArgumentException("Presence TTL must be positive.");
        this.redis = redis;
        this.ttlMillis = Duration.ofSeconds(ttlSeconds).toMillis();
    }

    @Override
    public void online(UUID userId, String connectionId) {
        requireIds(userId, connectionId);
        long now = System.currentTimeMillis();
        String key = key(userId);
        ZSetOperations<String, String> connections = redis.opsForZSet();
        connections.removeRangeByScore(key, 0, now - ttlMillis);
        connections.add(key, connectionId, now);
        redis.expire(key, Duration.ofMillis(ttlMillis * 2));
    }

    @Override
    public void offline(UUID userId, String connectionId) {
        requireIds(userId, connectionId);
        redis.opsForZSet().remove(key(userId), connectionId);
    }

    @Override
    public boolean isOnline(UUID userId) {
        if (userId == null) return false;
        try {
            long now = System.currentTimeMillis();
            String key = key(userId);
            ZSetOperations<String, String> connections = redis.opsForZSet();
            connections.removeRangeByScore(key, 0, now - ttlMillis);
            Long count = connections.count(key, now - ttlMillis, Double.POSITIVE_INFINITY);
            return count != null && count > 0;
        } catch (RuntimeException exception) {
            // Presence is advisory; social reads should still work during a Redis outage.
            return false;
        }
    }

    @Override
    public Set<UUID> onlineAmong(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Set.of();
        Set<UUID> online = new LinkedHashSet<>();
        for (UUID userId : new LinkedHashSet<>(userIds)) {
            if (isOnline(userId)) online.add(userId);
        }
        return Set.copyOf(online);
    }

    private static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }

    private static void requireIds(UUID userId, String connectionId) {
        if (userId == null || connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("A user and connection ID are required.");
        }
    }
}
