package com.nexus.server.messaging;

import java.util.UUID;

public interface MessagingRepository {
    ConversationView createDirectConversation(UUID creatorId, UUID participantId);
    ConversationPage conversations(UUID userId, UUID cursor, int limit);
    MessagePage messages(UUID userId, UUID conversationId, UUID after, int limit);
    MessageView sendMessage(UUID userId, UUID conversationId, String idempotencyKey, String content);
    void deleteMessage(UUID userId, UUID conversationId, UUID messageId, MessageDeletionScope scope);
    void clearHistory(UUID userId, UUID conversationId);
}
