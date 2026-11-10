package com.haichutieu.chatsystem.server.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationServiceTest {

    @Test
    void loginIssuesOpaqueTokenAndStoresOnlyItsHash() {
        PasswordHasher hasher = new PasswordHasher();
        String passwordHash = hasher.hash("correct horse battery staple");
        UserProfile profile = new UserProfile(UUID.randomUUID(), "alice", "Alice", "alice@example.com",
                "Address", java.time.LocalDate.of(1998, 4, 12), "female", "offline");
        UserAccountRepository users = new UserAccountRepository() {
            @Override public UserProfile create(NewUserRegistration registration) { throw new UnsupportedOperationException(); }
            @Override public Optional<LoginAccount> findLogin(String username) {
                return Optional.of(new LoginAccount(profile, passwordHash, false));
            }
        };
        CapturingSessionRepository sessions = new CapturingSessionRepository();
        AuthenticationService service = new AuthenticationService(users, sessions, hasher,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        SignedInSession result = service.login(new LoginRequest(" ALICE ", "correct horse battery staple"));

        assertThat(result.profile()).isEqualTo(profile);
        assertThat(result.token()).hasSizeGreaterThan(30).doesNotContain("alice");
        assertThat(sessions.tokenHash).hasSize(64).isNotEqualTo(result.token());
        assertThat(sessions.userId).isEqualTo(profile.id());
        assertThat(sessions.expiresAt).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
        service.authenticate(result.token());
        assertThat(sessions.authenticatedHash).isEqualTo(sessions.tokenHash);
        service.logout(result.token());
        assertThat(sessions.revokedHash).isEqualTo(sessions.tokenHash);
    }

    @Test
    void invalidPasswordAndLockedAccountAreRejected() {
        PasswordHasher hasher = new PasswordHasher();
        UserProfile profile = new UserProfile(UUID.randomUUID(), "alice", "Alice", "alice@example.com",
                "Address", java.time.LocalDate.of(1998, 4, 12), "female", "offline");
        UserAccountRepository users = new UserAccountRepository() {
            @Override public UserProfile create(NewUserRegistration registration) { throw new UnsupportedOperationException(); }
            @Override public Optional<LoginAccount> findLogin(String username) {
                return Optional.of(new LoginAccount(profile, hasher.hash("correct horse battery staple"), true));
            }
        };
        AuthenticationService service = new AuthenticationService(users, new CapturingSessionRepository(), hasher,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "correct horse battery staple")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void changingPasswordVerifiesCurrentPasswordAndRevokesOtherSessions() {
        PasswordHasher hasher = new PasswordHasher();
        UserProfile profile = new UserProfile(UUID.randomUUID(), "alice", "Alice", "alice@example.com",
                "Address", java.time.LocalDate.of(1998, 4, 12), "female", "offline");
        String oldHash = hasher.hash("current password long");
        UserAccountRepository users = new UserAccountRepository() {
            @Override public UserProfile create(NewUserRegistration registration) { throw new UnsupportedOperationException(); }
            @Override public Optional<LoginAccount> findLogin(String username) { return Optional.empty(); }
            @Override public Optional<String> findPasswordHash(UUID id) { return Optional.of(oldHash); }
        };
        CapturingSessionRepository sessions = new CapturingSessionRepository();
        AuthenticationService service = new AuthenticationService(users, sessions, hasher,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        service.changePassword(profile.id(), "current-session-token", new PasswordChangeRequest(
                "current password long", "new password with enough length"));

        assertThat(sessions.newPasswordHash).startsWith("$argon2id$");
        assertThat(hasher.matches("new password with enough length", sessions.newPasswordHash)).isTrue();
        assertThat(sessions.currentSessionHash).isEqualTo(AuthenticationService.hashToken("current-session-token"));
        assertThat(sessions.updatedUserId).isEqualTo(profile.id());
    }

    private static final class CapturingSessionRepository implements SessionRepository {
        private String tokenHash;
        private UUID userId;
        private Instant expiresAt;
        private String authenticatedHash;
        private String revokedHash;
        private String newPasswordHash;
        private String currentSessionHash;
        private UUID updatedUserId;

        @Override public void create(String hash, UUID id, Instant expiry) {
            tokenHash = hash;
            userId = id;
            expiresAt = expiry;
        }
        @Override public Optional<SessionIdentity> authenticate(String hash) {
            authenticatedHash = hash;
            return Optional.empty();
        }
        @Override public void revoke(String hash) { revokedHash = hash; }
        @Override public boolean changePassword(UUID id, String expectedHash, String newHash, String currentHash) {
            updatedUserId = id;
            newPasswordHash = newHash;
            currentSessionHash = currentHash;
            return true;
        }
    }
}
