package com.nexus.server.identity;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
public class RedisRateLimitStore implements RateLimitStore {

    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration expiry) {
        Long count = redis.execute(INCREMENT, List.of(key), Long.toString(Math.max(1, expiry.toSeconds())));
        if (count == null) {
            throw new IllegalStateException("Redis did not return the rate-limit count.");
        }
        return count;
    }
}
