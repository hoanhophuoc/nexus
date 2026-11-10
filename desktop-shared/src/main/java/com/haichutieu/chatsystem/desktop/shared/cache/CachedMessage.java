package com.haichutieu.chatsystem.desktop.shared.cache;

import java.time.Instant;
import java.util.UUID;

public record CachedMessage(UUID id, UUID conversationId, String senderName, String content, Instant createdAt,
                            Instant deletedForEveryoneAt) {
    public CachedMessage(UUID id, UUID conversationId, String senderName, String content, Instant createdAt) {
        this(id, conversationId, senderName, content, createdAt, null);
    }

    public CachedMessage {
        if (id == null || conversationId == null || senderName == null || content == null || createdAt == null) {
            throw new IllegalArgumentException("Cached messages require identity, content, and creation time.");
        }
        if (content.length() > 10_000) throw new IllegalArgumentException("Message exceeds 10,000 characters.");
    }
}
