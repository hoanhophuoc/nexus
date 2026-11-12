package com.nexus.server.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void hashesAndVerifiesPasswordsWithArgon2id() {
        String hash = passwordHasher.hash("correct horse battery staple");

        assertThat(hash).startsWith("$argon2id$");
        assertThat(passwordHasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(passwordHasher.matches("wrong password", hash)).isFalse();
    }

    @Test
    void generatesDifferentHashesForTheSamePassword() {
        String first = passwordHasher.hash("same password");
        String second = passwordHasher.hash("same password");

        assertThat(first).isNotEqualTo(second);
        assertThat(passwordHasher.matches("same password", first)).isTrue();
        assertThat(passwordHasher.matches("same password", second)).isTrue();
    }

    @Test
    void treatsMalformedHashesAsNoMatch() {
        assertThat(passwordHasher.matches("secret", "not-a-hash")).isFalse();
        assertThat(passwordHasher.matches("secret", null)).isFalse();
    }
}
