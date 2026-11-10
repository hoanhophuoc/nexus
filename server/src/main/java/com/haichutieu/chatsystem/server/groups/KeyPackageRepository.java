package com.haichutieu.chatsystem.server.groups;

import java.util.Optional;
import java.util.UUID;

public interface KeyPackageRepository {
    void store(UUID userId, byte[] keyPackage);

    Optional<byte[]> find(UUID userId);
}
