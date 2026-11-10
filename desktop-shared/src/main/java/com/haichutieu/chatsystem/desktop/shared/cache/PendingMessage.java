package com.haichutieu.chatsystem.desktop.shared.cache;

import java.time.Instant;
import java.util.UUID;

public record PendingMessage(UUID localId, UUID conversationId, String idempotencyKey, String content,
                             Instant createdAt, long queueOrder) {
}
