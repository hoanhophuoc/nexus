package com.nexus.desktop.shared.cache;

import java.time.Instant;
import java.util.UUID;

public record MessageSearchResult(UUID conversationId, UUID messageId, UUID senderId, String senderName,
                                  String preview, Instant createdAt) {
}
