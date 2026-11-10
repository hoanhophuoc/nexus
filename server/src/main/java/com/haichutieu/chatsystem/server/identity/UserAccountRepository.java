package com.haichutieu.chatsystem.server.identity;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository {
    UserProfile create(NewUserRegistration registration);
    default Optional<LoginAccount> findLogin(String normalizedUsername) { return Optional.empty(); }
    default Optional<UserProfile> findProfile(UUID userId) { return Optional.empty(); }
    default Optional<UserProfile> updateProfile(UUID userId, ProfileUpdateRequest update) { return Optional.empty(); }
    default Optional<String> findPasswordHash(UUID userId) { return Optional.empty(); }
}
