package com.nexus.server.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {
    void create(String tokenHash, UUID userId, Instant expiresAt);
    Optional<SessionIdentity> authenticate(String tokenHash);
    void revoke(String tokenHash);
    default boolean changePassword(UUID userId, String expectedHash, String newHash, String currentSessionHash) {
        throw new UnsupportedOperationException();
    }
}
