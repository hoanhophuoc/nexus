package com.haichutieu.chatsystem.server.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetRepository {
    Optional<PasswordResetAccount> findAccount(String normalizedEmail);
    Optional<PasswordResetAccount> findAccountById(UUID userId);
    void create(String tokenHash, UUID userId, Instant expiresAt);
    void invalidate(String tokenHash);
    boolean consume(String tokenHash, String newPasswordHash);
}
