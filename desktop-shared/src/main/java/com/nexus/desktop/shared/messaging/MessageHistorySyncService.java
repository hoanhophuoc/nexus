package com.nexus.desktop.shared.messaging;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.cache.EncryptedMessageCache;
import com.nexus.desktop.shared.cache.PendingMessage;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.api.DefaultApi;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.ConversationPage;
import com.nexus.sdk.model.Message;
import com.nexus.sdk.model.MessagePage;
import com.nexus.sdk.model.MessageSubmission;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class MessageHistorySyncService {
    private static final int PAGE_SIZE = 200;
    private final DefaultApi api;
    private final EncryptedMessageCache cache;
    private final tools.jackson.databind.ObjectMapper eventMapper = JsonMapper.builder().build();

    public MessageHistorySyncService(DefaultApi api, EncryptedMessageCache cache) {
        this.api = api;
        this.cache = cache;
    }

    public int syncAllDirectConversations() throws ApiException, IOException {
        String cursor = null;
        int count = 0;
        do {
            ConversationPage page = api.listConversations(cursor, 100);
            if (page == null || page.getItems() == null) throw new IOException("Conversation sync returned no page.");
            for (Conversation conversation : page.getItems()) {
                if (conversation.getType() == Conversation.TypeEnum.DIRECT) {
                    count += syncConversation(conversation.getId());
                }
            }
            String next = page.getNextCursor();
            if (next != null && (next.isBlank() || next.equals(cursor))) {
                throw new IOException("Conversation sync cursor did not advance.");
            }
            cursor = next;
        } while (cursor != null);
        return count;
    }

    public int syncConversation(UUID conversationId) throws ApiException, IOException {
        String cursor = cache.historyCursor(conversationId);
        int count = 0;
        do {
            MessagePage page = api.getMessages(conversationId, cursor, PAGE_SIZE);
            if (page == null || page.getItems() == null) throw new IOException("Message sync returned no page.");
            List<CachedMessage> updates = new ArrayList<>(page.getItems().size());
            for (Message message : page.getItems()) {
                updates.add(cached(message));
            }
            String lastMessageId = updates.isEmpty() ? cursor : updates.getLast().id().toString();
            cache.syncPage(conversationId, updates, lastMessageId);
            count += updates.size();
            String next = page.getNextCursor();
            if (next != null && (next.isBlank() || next.equals(cursor) || updates.isEmpty())) {
                throw new IOException("Message sync cursor did not advance.");
            }
            cursor = next;
        } while (cursor != null);
        return count;
    }

    public PendingMessage queueMessage(UUID conversationId, String content) throws IOException {
        return cache.enqueue(conversationId, content);
    }

    /** Sends pending messages in their encrypted local queue order and stops at the first failure. */
    public synchronized int flushPendingMessages() throws ApiException, IOException {
        int sent = 0;
        for (PendingMessage pending : cache.pendingMessages()) {
            Message message = api.sendMessage(pending.conversationId(), pending.idempotencyKey(),
                    new MessageSubmission().content(pending.content()));
            cache.completePending(pending.localId(), cached(message));
            sent++;
        }
        return sent;
    }

    public void hideMessageForMe(UUID conversationId, UUID messageId) throws ApiException, IOException {
        api.deleteMessage(conversationId, messageId, "me");
        cache.hideForMe(messageId);
    }

    public void deleteMessageForEveryone(UUID conversationId, UUID messageId) throws ApiException, IOException {
        api.deleteMessage(conversationId, messageId, "everyone");
        cache.markDeletedForEveryone(messageId, Instant.now());
    }

    public void clearHistory(UUID conversationId) throws ApiException, IOException {
        api.clearConversationHistory(conversationId);
        cache.clearConversation(conversationId);
    }

    public void acceptRealtimeEvent(String json) throws IOException {
        JsonNode event;
        try {
            event = eventMapper.readTree(json);
        } catch (RuntimeException exception) {
            throw new IOException("Malformed chat event.", exception);
        }
        JsonNode type = event.get("type");
        if (type == null || !type.isString()) throw new IOException("Chat event has no type.");
        try {
            switch (type.asText()) {
                case "MessageCreated" -> {
                    JsonNode message = event.get("message");
                    cache.upsert(new CachedMessage(UUID.fromString(message.get("id").asText()),
                            UUID.fromString(message.get("conversationId").asText()),
                            UUID.fromString(message.get("senderId").asText()).toString(),
                            message.get("content").asText(), Instant.parse(message.get("createdAt").asText()),
                            message.hasNonNull("deletedForEveryoneAt")
                                    ? Instant.parse(message.get("deletedForEveryoneAt").asText()) : null));
                }
                case "MessageDeleted" -> cache.markDeletedForEveryone(
                        UUID.fromString(event.get("messageId").asText()), Instant.now());
                case "MessageHidden" -> {
                    UUID owner = UUID.fromString(event.get("userId").asText());
                    if (!cache.ownerId().equals(owner)) throw new IOException("Chat event targets another account.");
                    cache.hideForMe(UUID.fromString(event.get("messageId").asText()));
                }
                default -> throw new IOException("Unsupported chat event type.");
            }
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IOException("Invalid chat event payload.", exception);
        }
    }

    private static CachedMessage cached(Message message) {
        return new CachedMessage(message.getId(), message.getConversationId(), message.getSenderId().toString(),
                message.getContent(), message.getCreatedAt().toInstant(), message.getDeletedForEveryoneAt() == null
                ? null : message.getDeletedForEveryoneAt().toInstant());
    }
}
