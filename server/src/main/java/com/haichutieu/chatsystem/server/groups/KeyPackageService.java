package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.identity.UserNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class KeyPackageService {
    static final int MAX_KEY_PACKAGE_BYTES = 65_536;

    private final KeyPackageRepository keyPackages;

    public KeyPackageService(KeyPackageRepository keyPackages) {
        this.keyPackages = keyPackages;
    }

    public void publish(UUID userId, byte[] keyPackage) {
        if (keyPackage == null || keyPackage.length == 0) {
            throw new IllegalArgumentException("Key package must not be empty.");
        }
        if (keyPackage.length > MAX_KEY_PACKAGE_BYTES) {
            throw new MlsPayloadTooLargeException("Key package exceeds " + MAX_KEY_PACKAGE_BYTES + " bytes.");
        }
        keyPackages.store(userId, keyPackage);
    }

    public byte[] find(UUID userId) {
        return keyPackages.find(userId)
                .orElseThrow(() -> new UserNotFoundException());
    }
}
