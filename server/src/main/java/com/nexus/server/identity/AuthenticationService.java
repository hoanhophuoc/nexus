package com.nexus.server.identity;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public final class AuthenticationService {

    static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository users;
    private final SessionRepository sessions;
    private final PasswordHasher passwords;
    private final Clock clock;

    @Autowired
    public AuthenticationService(UserAccountRepository users, SessionRepository sessions, PasswordHasher passwords) {
        this(users, sessions, passwords, Clock.systemUTC());
    }

    AuthenticationService(UserAccountRepository users, SessionRepository sessions, PasswordHasher passwords, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
    }

    public SignedInSession login(LoginRequest request) {
        Optional<LoginAccount> account = users.findLogin(request.username().strip().toLowerCase(Locale.ROOT));
        if (account.isEmpty() || account.get().locked()
                || !passwords.matches(request.password(), account.get().passwordHash())) {
            throw new InvalidCredentialsException();
        }

        byte[] rawToken = new byte[32];
        RANDOM.nextBytes(rawToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
        Instant expiresAt = clock.instant().plus(SESSION_LIFETIME);
        sessions.create(hashToken(token), account.get().profile().id(), expiresAt);
        return new SignedInSession(account.get().profile(), token);
    }

    public Optional<SessionIdentity> authenticate(String token) {
        return token == null || token.isBlank() ? Optional.empty() : sessions.authenticate(hashToken(token));
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.revoke(hashToken(token));
        }
    }

    public void changePassword(UUID userId, String currentToken, PasswordChangeRequest request) {
        String currentHash = users.findPasswordHash(userId).orElseThrow(InvalidCredentialsException::new);
        if (!passwords.matches(request.currentPassword(), currentHash)
                || !sessions.changePassword(userId, currentHash, passwords.hash(request.newPassword()), hashToken(currentToken))) {
            throw new InvalidCredentialsException();
        }
    }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }
}
