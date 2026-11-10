package com.haichutieu.chatsystem.desktop.shared.cache;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;

/** Derives the local cache key at login; neither the password nor the key is persisted. */
public final class CacheKeyDerivation {
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;

    private CacheKeyDerivation() { }

    public static SecretKeySpec derive(char[] password, UUID accountId) throws GeneralSecurityException {
        if (password == null || password.length == 0 || accountId == null) {
            throw new IllegalArgumentException("A password and account ID are required.");
        }
        ByteBuffer salt = ByteBuffer.allocate(16 + 21);
        salt.put("chat-history-cache-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        salt.putLong(accountId.getMostSignificantBits()).putLong(accountId.getLeastSignificantBits());
        PBEKeySpec spec = new PBEKeySpec(password, salt.array(), ITERATIONS, KEY_BITS);
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
