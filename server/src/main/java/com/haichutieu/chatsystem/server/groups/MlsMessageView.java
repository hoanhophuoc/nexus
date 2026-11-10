package com.haichutieu.chatsystem.server.groups;

import java.time.Instant;
import java.util.UUID;

public record MlsMessageView(UUID id, long epoch, String kind, byte[] ciphertext, Instant createdAt) {
}
