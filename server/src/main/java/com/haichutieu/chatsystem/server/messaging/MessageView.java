package com.haichutieu.chatsystem.server.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageView(UUID id, UUID conversationId, UUID senderId, String content, Instant createdAt,
                          Instant deletedForEveryoneAt) {
}
