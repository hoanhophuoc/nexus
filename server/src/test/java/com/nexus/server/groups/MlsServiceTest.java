package com.nexus.server.groups;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MlsServiceTest {
    private static final String KEY = "mls-idempotency-key-0001";

    private final MlsRepository repository = mock(MlsRepository.class);
    private final MlsService mls = new MlsService(repository);
    private final UUID actor = UUID.randomUUID();
    private final UUID group = UUID.randomUUID();

    @Test
    void rejectsEmptyAndOversizedPayloadsBeforeTouchingStorage() {
        assertThatThrownBy(() -> mls.submitCommit(actor, group, 0, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mls.submitCommit(actor, group, 0, new byte[MlsService.MAX_COMMIT_BYTES + 1]))
                .isInstanceOf(MlsPayloadTooLargeException.class);
        assertThatThrownBy(() -> mls.sendMessage(actor, group, 0, KEY,
                new byte[MlsService.MAX_APPLICATION_BYTES + 1]))
                .isInstanceOf(MlsPayloadTooLargeException.class);
        assertThatThrownBy(() -> mls.storeRecoveryPackage(actor, group,
                new byte[MlsService.MAX_RECOVERY_BYTES + 1]))
                .isInstanceOf(MlsPayloadTooLargeException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNegativeEpochsAndShortIdempotencyKeys() {
        assertThatThrownBy(() -> mls.submitCommit(actor, group, -1, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mls.sendMessage(actor, group, 0, "short", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void forwardsValidCiphertextToStorage() {
        byte[] payload = {1, 2, 3};
        when(repository.sendMessage(actor, group, 0, KEY, payload))
                .thenReturn(new MlsMessageView(UUID.randomUUID(), 0, "application", payload,
                        java.time.Instant.now()));

        mls.sendMessage(actor, group, 0, KEY, payload);

        verify(repository).sendMessage(actor, group, 0, KEY, payload);
    }
}
