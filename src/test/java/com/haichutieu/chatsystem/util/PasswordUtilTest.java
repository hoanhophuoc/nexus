package com.haichutieu.chatsystem.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordUtilTest {

    // Legacy hash documented in README.md for the password "admin123".
    private static final String LEGACY_BCRYPT = "$2a$10$OpmVhM0U6OBNC6kmfqchGuW0Ypkx.oz5Q8pJ6RSGmZ/t/7Ox9a/DW";

    @Test
    void shouldHashWithArgon2idAndVerify() {
        String hash = PasswordUtil.hash("correct horse 42");

        assertThat(hash).startsWith("$argon2id$");
        assertThat(PasswordUtil.verify("correct horse 42", hash)).isTrue();
        assertThat(PasswordUtil.verify("wrong horse 42", hash)).isFalse();
    }

    @Test
    void shouldUseAUniqueSaltPerHash() {
        String first = PasswordUtil.hash("same-password-1");
        String second = PasswordUtil.hash("same-password-1");

        assertThat(first).isNotEqualTo(second);
        assertThat(PasswordUtil.verify("same-password-1", first)).isTrue();
        assertThat(PasswordUtil.verify("same-password-1", second)).isTrue();
    }

    @Test
    void shouldVerifyLegacyBcryptHash() {
        assertThat(PasswordUtil.verify("admin123", LEGACY_BCRYPT)).isTrue();
        assertThat(PasswordUtil.verify("not-admin123", LEGACY_BCRYPT)).isFalse();
    }

    @Test
    void shouldRequireRehashOnlyForNonArgon2idHashes() {
        assertThat(PasswordUtil.needsRehash(LEGACY_BCRYPT)).isTrue();
        assertThat(PasswordUtil.needsRehash(PasswordUtil.hash("new-password-1"))).isFalse();
        assertThat(PasswordUtil.needsRehash(null)).isTrue();
        assertThat(PasswordUtil.needsRehash("plaintext")).isTrue();
    }

    @Test
    void shouldTreatMalformedOrMissingHashesAsFailedVerification() {
        assertThat(PasswordUtil.verify("secret", null)).isFalse();
        assertThat(PasswordUtil.verify(null, LEGACY_BCRYPT)).isFalse();
        assertThat(PasswordUtil.verify("secret", "")).isFalse();
        assertThat(PasswordUtil.verify("secret", "not-a-hash")).isFalse();
        assertThat(PasswordUtil.verify("secret", "$2a$10$too-short")).isFalse();
    }
}
