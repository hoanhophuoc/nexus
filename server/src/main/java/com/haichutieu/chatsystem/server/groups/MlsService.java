package com.haichutieu.chatsystem.server.groups;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MlsService {
    static final int MAX_COMMIT_BYTES = 1_048_576;
    static final int MAX_APPLICATION_BYTES = 65_536;
    static final int MAX_RECOVERY_BYTES = 1_048_576;

    private final MlsRepository mls;

    public MlsService(MlsRepository mls) {
        this.mls = mls;
    }

    public void submitCommit(UUID actorId, UUID groupId, long expectedEpoch, byte[] commit) {
        requireEpoch(expectedEpoch);
        requirePayload(commit, MAX_COMMIT_BYTES, "MLS commit");
        mls.submitCommit(actorId, groupId, expectedEpoch, commit);
    }

    public MlsMessageView sendMessage(UUID actorId, UUID groupId, long epoch, String idempotencyKey,
                                      byte[] ciphertext) {
        requireEpoch(epoch);
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 characters.");
        }
        requirePayload(ciphertext, MAX_APPLICATION_BYTES, "MLS ciphertext");
        return mls.sendMessage(actorId, groupId, epoch, idempotencyKey, ciphertext);
    }

    public MlsMessagePage messages(UUID actorId, UUID groupId, UUID after, int limit) {
        return mls.messages(actorId, groupId, after, Math.clamp(limit, 1, 200));
    }

    public void storeRecoveryPackage(UUID actorId, UUID groupId, byte[] encryptedPackage) {
        requirePayload(encryptedPackage, MAX_RECOVERY_BYTES, "recovery package");
        mls.storeRecoveryPackage(actorId, groupId, encryptedPackage);
    }

    public byte[] recoveryPackage(UUID actorId, UUID groupId) {
        return mls.recoveryPackage(actorId, groupId);
    }

    private static void requireEpoch(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("The MLS epoch must not be negative.");
        }
    }

    private static void requirePayload(byte[] payload, int maximum, String name) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty.");
        }
        if (payload.length > maximum) {
            throw new MlsPayloadTooLargeException(name + " exceeds " + maximum + " bytes.");
        }
    }
}
