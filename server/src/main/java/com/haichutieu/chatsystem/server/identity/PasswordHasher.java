package com.haichutieu.chatsystem.server.identity;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import org.springframework.stereotype.Component;

@Component
public final class PasswordHasher {

    private static final Argon2Function ARGON2ID =
            Argon2Function.getInstance(32768, 3, 1, 32, Argon2.ID);

    public String hash(String rawPassword) {
        return Password.hash(rawPassword).with(ARGON2ID).getResult();
    }

    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null || !passwordHash.startsWith("$argon2id$")) {
            return false;
        }
        try {
            return Password.check(rawPassword, passwordHash)
                    .with(Argon2Function.getInstanceFromHash(passwordHash));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
