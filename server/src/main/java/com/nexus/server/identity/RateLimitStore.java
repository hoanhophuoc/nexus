package com.nexus.server.identity;

import java.time.Duration;

public interface RateLimitStore {
    long increment(String key, Duration expiry);
}
