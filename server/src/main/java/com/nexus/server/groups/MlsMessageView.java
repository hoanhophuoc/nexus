package com.nexus.server.groups;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record MlsMessageView(UUID id, long epoch, String kind, byte[] ciphertext, Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof MlsMessageView view && Objects.equals(id, view.id) && epoch == view.epoch
                && Objects.equals(kind, view.kind) && Arrays.equals(ciphertext, view.ciphertext)
                && Objects.equals(createdAt, view.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, epoch, kind, Arrays.hashCode(ciphertext), createdAt);
    }

    @Override
    public String toString() {
        return "MlsMessageView[" + id + ", epoch=" + epoch + ", kind=" + kind + "]";
    }
}
