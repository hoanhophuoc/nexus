package com.nexus.desktop.shared.cache;

import java.time.Instant;
import java.util.UUID;

/**
 * One synchronized message. {@code senderId} is identity; {@code senderName} is the display name when a
 * source knows one. They are separate fields because a name is not an identity, and the row that decides
 * "this is mine" must never compare against a display string.
 *
 * <p>{@code senderId} is null only for rows migrated from cache format 3, which stored a single field that
 * held either the sender id or a resolved group member name.</p>
 */
public record CachedMessage(UUID id, UUID conversationId, UUID senderId, String senderName, String content,
                            Instant createdAt, Instant deletedForEveryoneAt) {
    public CachedMessage(UUID id, UUID conversationId, UUID senderId, String senderName, String content,
                         Instant createdAt) {
        this(id, conversationId, senderId, senderName, content, createdAt, null);
    }

    public CachedMessage {
        if (id == null || conversationId == null || senderName == null || content == null || createdAt == null) {
            throw new IllegalArgumentException("Cached messages require identity, content, and creation time.");
        }
        if (content.length() > 10_000) throw new IllegalArgumentException("Message exceeds 10,000 characters.");
    }
}
