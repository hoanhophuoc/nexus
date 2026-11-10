package com.haichutieu.chatsystem.desktop.shared.cache;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyDerivationTest {
    @Test
    void derivesAccountScopedKeyWithoutPersistingThePassword() throws Exception {
        char[] password = "local cache passphrase".toCharArray();
        UUID account = UUID.randomUUID();
        byte[] first = CacheKeyDerivation.derive(password, account).getEncoded();
        byte[] same = CacheKeyDerivation.derive(password, account).getEncoded();
        byte[] otherAccount = CacheKeyDerivation.derive(password, UUID.randomUUID()).getEncoded();

        assertThat(first).hasSize(32).containsExactly(same);
        assertThat(Arrays.equals(first, otherAccount)).isFalse();
        assertThat(password).containsExactly("local cache passphrase".toCharArray());
    }
}
