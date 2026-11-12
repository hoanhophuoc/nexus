package com.nexus.server.messaging;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MessagingService {

    private final MessagingRepository messages;

    public MessagingService(MessagingRepository messages) {
        this.messages = messages;
    }

    public ConversationView createDirectConversation(UUID creatorId, UUID participantId) {
        if (creatorId.equals(participantId)) throw new ConversationForbiddenException();
        return messages.createDirectConversation(creatorId, participantId);
    }

    public ConversationPage conversations(UUID userId, UUID cursor, int limit) {
        return messages.conversations(userId, cursor, Math.clamp(limit, 1, 100));
    }

    public MessagePage messages(UUID userId, UUID conversationId, UUID after, int limit) {
        return messages.messages(userId, conversationId, after, Math.clamp(limit, 1, 200));
    }

    public MessageView sendMessage(UUID userId, UUID conversationId, String idempotencyKey, String content) {
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 characters.");
        }
        return messages.sendMessage(userId, conversationId, idempotencyKey, content);
    }

    public void deleteMessage(UUID userId, UUID conversationId, UUID messageId, MessageDeletionScope scope) {
        messages.deleteMessage(userId, conversationId, messageId, scope);
    }

    public void clearHistory(UUID userId, UUID conversationId) {
        messages.clearHistory(userId, conversationId);
    }
}
