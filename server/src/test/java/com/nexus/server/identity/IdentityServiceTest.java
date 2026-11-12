package com.nexus.server.identity;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityServiceTest {

    @Test
    void registrationNormalizesUniqueFieldsAndReturnsOnlyThePublicProfile() {
        CapturingUserAccountRepository repository = new CapturingUserAccountRepository();
        PasswordHasher passwordHasher = new PasswordHasher();
        IdentityService service = new IdentityService(repository, passwordHasher);
        RegistrationRequest request = new RegistrationRequest(
                "  Alice.Example ", "Alice Example", " Alice@Example.com ", " 1 Main Street ",
                LocalDate.of(1998, 4, 12), "female", "correct horse battery staple");

        UserProfile profile = service.register(request);

        assertThat(repository.registration.username()).isEqualTo("Alice.Example");
        assertThat(repository.registration.usernameNormalized()).isEqualTo("alice.example");
        assertThat(repository.registration.email()).isEqualTo("Alice@Example.com");
        assertThat(repository.registration.emailNormalized()).isEqualTo("alice@example.com");
        assertThat(repository.registration.passwordHash()).startsWith("$argon2id$");
        assertThat(passwordHasher.matches(request.password(), repository.registration.passwordHash())).isTrue();
        assertThat(profile).isEqualTo(repository.profile);
        assertThat(profile.toString()).doesNotContain("password");
    }

    @Test
    void duplicateNormalizedAccountsReturnAConflict() {
        UserAccountRepository repository = registration -> {
            throw new DuplicateKeyException("unique constraint");
        };
        IdentityService service = new IdentityService(repository, new PasswordHasher());
        RegistrationRequest request = new RegistrationRequest(
                "alice", "Alice", "alice@example.com", "Address", LocalDate.of(1998, 4, 12),
                "female", "correct horse battery staple");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(AccountConflictException.class);
    }

    @Test
    void profileUpdateTrimsFieldsAndNormalizesEmail() {
        CapturingUserAccountRepository repository = new CapturingUserAccountRepository();
        IdentityService service = new IdentityService(repository, new PasswordHasher());
        ProfileUpdateRequest request = new ProfileUpdateRequest(" Alice Example ", " ALICE@EXAMPLE.COM ",
                " 2 Main Street ", null, null);

        service.updateProfile(UUID.randomUUID(), request);

        assertThat(repository.update.fullName()).isEqualTo("Alice Example");
        assertThat(repository.update.email()).isEqualTo("alice@example.com");
        assertThat(repository.update.address()).isEqualTo("2 Main Street");
    }

    private static final class CapturingUserAccountRepository implements UserAccountRepository {
        private NewUserRegistration registration;
        private UserProfile profile;
        private ProfileUpdateRequest update;

        @Override
        public UserProfile create(NewUserRegistration newUser) {
            registration = newUser;
            profile = new UserProfile(
                    UUID.randomUUID(), newUser.username(), newUser.fullName(), newUser.email(),
                    newUser.address(), newUser.birthDate(), newUser.gender(), "offline");
            return profile;
        }

        @Override
        public java.util.Optional<UserProfile> updateProfile(UUID userId, ProfileUpdateRequest request) {
            update = request;
            return java.util.Optional.of(new UserProfile(userId, "alice", request.fullName(), request.email(),
                    request.address(), request.birthDate(), request.gender(), "offline"));
        }
    }
}
