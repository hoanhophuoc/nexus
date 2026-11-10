package com.haichutieu.chatsystem.server.groups;

import java.util.UUID;

public interface MlsRepository {
    void submitCommit(UUID actorId, UUID groupId, long expectedEpoch, byte[] commit);

    MlsMessageView sendMessage(UUID actorId, UUID groupId, long epoch, String idempotencyKey, byte[] ciphertext);

    MlsMessagePage messages(UUID actorId, UUID groupId, UUID after, int limit);

    void storeRecoveryPackage(UUID actorId, UUID groupId, byte[] encryptedPackage);

    byte[] recoveryPackage(UUID actorId, UUID groupId);
}
