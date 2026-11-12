package com.nexus.server.identity;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public final class PasswordResetService {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PasswordResetRepository resets;
    private final PasswordResetNotifier notifier;
    private final PasswordHasher passwords;
    private final RateLimitService rateLimits;
    private final Clock clock;

    @Autowired
    public PasswordResetService(PasswordResetRepository resets, PasswordResetNotifier notifier,
                                PasswordHasher passwords, RateLimitService rateLimits) {
        this(resets, notifier, passwords, rateLimits, Clock.systemUTC());
    }

    PasswordResetService(PasswordResetRepository resets, PasswordResetNotifier notifier,
                         PasswordHasher passwords, RateLimitService rateLimits, Clock clock) {
        this.resets = resets;
        this.notifier = notifier;
        this.passwords = passwords;
        this.rateLimits = rateLimits;
        this.clock = clock;
    }

    public void request(String email, String remoteAddress) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        rateLimits.consume("password-reset-ip", remoteAddress, 10, Duration.ofHours(1));
        rateLimits.consume("password-reset-email", normalizedEmail, 3, Duration.ofHours(1));
        Optional<PasswordResetAccount> account = resets.findAccount(normalizedEmail);
        if (account.isEmpty()) {
            return;
        }

        issue(account.get());
    }

    /** administrator-initiated reset; authorization and auditing happen before this call */
    public void requestForUser(UUID userId) {
        issue(resets.findAccountById(userId).orElseThrow(UserNotFoundException::new));
    }

    private void issue(PasswordResetAccount account) {
        byte[] randomToken = new byte[32];
        RANDOM.nextBytes(randomToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken);
        String tokenHash = AuthenticationService.hashToken(token);
        resets.create(tokenHash, account.userId(), clock.instant().plus(TOKEN_LIFETIME));
        notifier.send(account.email(), token, tokenHash);
    }

    public void confirm(PasswordResetConfirmation confirmation, String remoteAddress) {
        rateLimits.consume("password-reset-confirm-ip", remoteAddress, 10, Duration.ofHours(1));
        rateLimits.consume("password-reset-confirm-token", confirmation.token(), 5, Duration.ofHours(1));
        String newHash = passwords.hash(confirmation.newPassword());
        if (!resets.consume(AuthenticationService.hashToken(confirmation.token()), newHash)) {
            throw new InvalidPasswordResetTokenException();
        }
    }
}
