package com.haichutieu.chatsystem.util;

import com.password4j.Argon2Function;
import com.password4j.BcryptFunction;
import com.password4j.Password;
import com.password4j.types.Argon2;

/**
 * Password hashing used by both the server and the desktop clients.
 *
 * <p>New passwords are hashed with Argon2id. Hashes created by earlier versions
 * (BCrypt, {@code $2a$...}) still verify and are upgraded on the next successful
 * login by the server.</p>
 */
public final class PasswordUtil {
    // OWASP-aligned Argon2id parameters: 32 MiB, 3 iterations, 1 lane, 32-byte output.
    private static final Argon2Function ARGON2 = Argon2Function.getInstance(32768, 3, 1, 32, Argon2.ID);
    private static final String ARGON2ID_PREFIX = "$argon2id$";

    private PasswordUtil() {
    }

    public static String hash(String rawPassword) {
        return Password.hash(rawPassword).with(ARGON2).getResult();
    }

    /**
     * Verifies a raw password against an Argon2id or legacy BCrypt hash. Malformed
     * hashes are treated as a failed match instead of surfacing an exception.
     */
    public static boolean verify(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            if (storedHash.startsWith("$2")) {
                return Password.check(rawPassword, storedHash)
                        .with(BcryptFunction.getInstanceFromHash(storedHash));
            }
            if (storedHash.startsWith("$argon2")) {
                return Password.check(rawPassword, storedHash)
                        .with(Argon2Function.getInstanceFromHash(storedHash));
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /**
     * @return true when the stored hash should be replaced with a fresh Argon2id hash
     */
    public static boolean needsRehash(String storedHash) {
        return storedHash == null || !storedHash.startsWith(ARGON2ID_PREFIX);
    }
}
