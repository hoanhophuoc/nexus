package com.nexus.server.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetServiceTest {

    @Test
    void resetRequestStoresOnlyHashedExpiringTokenAndSendsRawToken() {
        UUID userId = UUID.randomUUID();
        CapturingResetRepository repository = new CapturingResetRepository();
        repository.account = Optional.of(new PasswordResetAccount(userId, "alice@example.com"));
        CapturingNotifier notifier = new CapturingNotifier();
        PasswordResetService service = new PasswordResetService(repository, notifier, new PasswordHasher(),
                new RateLimitService((key, expiry) -> 1),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        service.request(" Alice@Example.com ", "127.0.0.1");

        assertThat(repository.normalizedEmail).isEqualTo("alice@example.com");
        assertThat(repository.userId).isEqualTo(userId);
        assertThat(repository.tokenHash).hasSize(64).isNotEqualTo(notifier.token);
        assertThat(repository.expiresAt).isEqualTo(Instant.parse("2026-01-01T00:15:00Z"));
        assertThat(notifier.recipient).isEqualTo("alice@example.com");
        assertThat(notifier.token).hasSizeGreaterThan(30);
    }

    @Test
    void unknownEmailIsAcceptedWithoutSendingMail() {
        CapturingResetRepository repository = new CapturingResetRepository();
        CapturingNotifier notifier = new CapturingNotifier();
        PasswordResetService service = new PasswordResetService(repository, notifier, new PasswordHasher(),
                new RateLimitService((key, expiry) -> 1), Clock.systemUTC());

        service.request("missing@example.com", "127.0.0.1");

        assertThat(repository.createCalls).isZero();
        assertThat(notifier.token).isNull();
    }

    @Test
    void confirmationHashesTheNewPasswordAndConsumesOnlyTheHashedToken() {
        CapturingResetRepository repository = new CapturingResetRepository();
        PasswordHasher passwords = new PasswordHasher();
        PasswordResetService service = new PasswordResetService(repository, new CapturingNotifier(), passwords,
                new RateLimitService((key, expiry) -> 1), Clock.systemUTC());
        String token = "a".repeat(43);

        service.confirm(new PasswordResetConfirmation(token, "a secure replacement password"), "127.0.0.1");

        assertThat(repository.consumedTokenHash).isEqualTo(AuthenticationService.hashToken(token));
        assertThat(repository.consumedTokenHash).hasSize(64).isNotEqualTo(token);
        assertThat(passwords.matches("a secure replacement password", repository.newPasswordHash)).isTrue();
    }

    private static final class CapturingResetRepository implements PasswordResetRepository {
        private Optional<PasswordResetAccount> account = Optional.empty();
        private String normalizedEmail;
        private UUID userId;
        private String tokenHash;
        private Instant expiresAt;
        private int createCalls;
        private String consumedTokenHash;
        private String newPasswordHash;

        @Override public Optional<PasswordResetAccount> findAccount(String email) {
            normalizedEmail = email;
            return account;
        }
        @Override public Optional<PasswordResetAccount> findAccountById(UUID id) {
            return account.filter(value -> value.userId().equals(id));
        }
        @Override public void create(String hash, UUID id, Instant expiry) {
            createCalls++;
            tokenHash = hash;
            userId = id;
            expiresAt = expiry;
        }
        @Override public void invalidate(String hash) { }
        @Override public boolean consume(String hash, String passwordHash) {
            consumedTokenHash = hash;
            newPasswordHash = passwordHash;
            return true;
        }
    }

    private static final class CapturingNotifier implements PasswordResetNotifier {
        private String recipient;
        private String token;
        @Override public void send(String email, String rawToken, String tokenHash) {
            recipient = email;
            token = rawToken;
        }
    }
}
