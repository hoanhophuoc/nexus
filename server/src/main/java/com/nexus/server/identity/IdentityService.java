package com.nexus.server.identity;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public final class IdentityService {

    private final UserAccountRepository users;
    private final PasswordHasher passwordHasher;

    public IdentityService(UserAccountRepository users, PasswordHasher passwordHasher) {
        this.users = users;
        this.passwordHasher = passwordHasher;
    }

    public UserProfile register(RegistrationRequest request) {
        String username = request.username().strip();
        String email = request.email().strip();
        try {
            return users.create(new NewUserRegistration(
                    UUID.randomUUID(), username, username.toLowerCase(Locale.ROOT),
                    request.fullName().strip(), email, email.toLowerCase(Locale.ROOT),
                    request.address().strip(), request.birthDate(), request.gender(),
                    passwordHasher.hash(request.password())));
        } catch (DuplicateKeyException exception) {
            throw new AccountConflictException();
        }
    }

    public UserProfile profile(UUID userId) {
        return users.findProfile(userId).orElseThrow(UserNotFoundException::new);
    }

    public UserProfile updateProfile(UUID userId, ProfileUpdateRequest request) {
        ProfileUpdateRequest normalized = new ProfileUpdateRequest(
                trim(request.fullName()), normalizeEmail(request.email()), trim(request.address()),
                request.birthDate(), request.gender());
        try {
            return users.updateProfile(userId, normalized).orElseThrow(UserNotFoundException::new);
        } catch (DuplicateKeyException exception) {
            throw new AccountConflictException();
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static String normalizeEmail(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
