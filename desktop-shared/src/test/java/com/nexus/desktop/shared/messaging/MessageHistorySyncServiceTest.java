package com.nexus.desktop.shared.messaging;

import com.nexus.desktop.shared.cache.EncryptedMessageCache;
import com.nexus.sdk.api.DefaultApi;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.ConversationPage;
import com.nexus.sdk.model.Message;
import com.nexus.sdk.model.MessagePage;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.model.MessageSubmission;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MessageHistorySyncServiceTest {
    @Test
    void syncsEveryDirectMessagePageIntoTheEncryptedLocalSearchStore() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        Message first = message(conversationId, senderId, "needle one", OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        Message second = message(conversationId, senderId, "needle two", OffsetDateTime.parse("2026-01-01T10:01:00Z"));
        List<String> cursors = new ArrayList<>();
        DefaultApi api = new DefaultApi() {
            @Override
            public ConversationPage listConversations(String cursor, Integer limit) {
                return new ConversationPage().items(List.of(
                        new Conversation().id(conversationId).type(Conversation.TypeEnum.DIRECT),
                        new Conversation().id(UUID.randomUUID()).type(Conversation.TypeEnum.GROUP)));
            }

            @Override
            public MessagePage getMessages(UUID requestedConversation, String after, Integer limit) {
                cursors.add(after);
                assertThat(requestedConversation).isEqualTo(conversationId);
                assertThat(limit).isEqualTo(200);
                if (after == null) return new MessagePage().items(List.of(first)).nextCursor(first.getId().toString());
                if (after.equals(first.getId().toString())) return new MessagePage().items(List.of(second));
                return new MessagePage().items(List.of());
            }
        };
        var keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        try (var cache = new EncryptedMessageCache(Files.createTempDirectory("message-sync").resolve("cache"),
                UUID.randomUUID(), keyGenerator.generateKey())) {
            var sync = new MessageHistorySyncService(api, cache);
            assertThat(sync.syncAllDirectConversations()).isEqualTo(2);
            assertThat(sync.syncAllDirectConversations()).isZero();
            assertThat(cursors).containsExactly(null, first.getId().toString(), second.getId().toString());
            assertThat(cache.search("NEEDLE")).extracting(result -> result.messageId())
                    .containsExactly(second.getId(), first.getId());
        }
    }

    @Test
    void offlineRetryReusesIdempotencyKeyAndKeepsQueuedConversationOrder() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        List<String> keys = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        boolean[] failOnce = {true};
        DefaultApi api = new DefaultApi() {
            @Override
            public Message sendMessage(UUID requestedConversation, String key, MessageSubmission submission)
                    throws ApiException {
                assertThat(requestedConversation).isEqualTo(conversationId);
                keys.add(key);
                sent.add(submission.getContent());
                if (failOnce[0]) {
                    failOnce[0] = false;
                    throw new ApiException(503, "offline");
                }
                return message(conversationId, senderId, submission.getContent(), OffsetDateTime.now());
            }
        };
        var keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        try (var cache = new EncryptedMessageCache(Files.createTempDirectory("message-retry").resolve("cache"),
                UUID.randomUUID(), keyGenerator.generateKey())) {
            var sync = new MessageHistorySyncService(api, cache);
            sync.queueMessage(conversationId, "first");
            sync.queueMessage(conversationId, "second");
            org.assertj.core.api.Assertions.assertThatThrownBy(sync::flushPendingMessages)
                    .isInstanceOf(ApiException.class);
            assertThat(cache.pendingMessages()).hasSize(2);

            assertThat(sync.flushPendingMessages()).isEqualTo(2);
            assertThat(keys.get(0)).isEqualTo(keys.get(1));
            assertThat(sent).containsExactly("first", "first", "second");
            assertThat(cache.pendingMessages()).isEmpty();
            assertThat(cache.search("first")).hasSize(1);
        }
    }

    @Test
    void appliesRealtimeEventsLocallyAndDoesNotUndoAUserHideOnDuplicateDelivery() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        try (var cache = new EncryptedMessageCache(Files.createTempDirectory("message-events").resolve("cache"),
                ownerId, keyGenerator.generateKey())) {
            var sync = new MessageHistorySyncService(new DefaultApi(), cache);
            String created = "{\"type\":\"MessageCreated\",\"eventId\":\"" + UUID.randomUUID()
                    + "\",\"cursor\":\"" + messageId + "\",\"message\":{\"id\":\"" + messageId
                    + "\",\"conversationId\":\"" + conversationId + "\",\"senderId\":\"" + ownerId
                    + "\",\"content\":\"private match\",\"createdAt\":\"2026-01-01T10:00:00Z\",\"deletedForEveryoneAt\":null}}";
            sync.acceptRealtimeEvent(created);
            assertThat(cache.search("private")).hasSize(1);

            sync.acceptRealtimeEvent("{\"type\":\"MessageHidden\",\"eventId\":\"" + UUID.randomUUID()
                    + "\",\"cursor\":\"" + messageId + "\",\"userId\":\"" + ownerId
                    + "\",\"conversationId\":\"" + conversationId + "\",\"messageId\":\"" + messageId + "\"}");
            sync.acceptRealtimeEvent(created);
            assertThat(cache.search("private")).isEmpty();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sync.acceptRealtimeEvent(
                    "{\"type\":\"MessageHidden\",\"userId\":\"" + UUID.randomUUID()
                            + "\",\"messageId\":\"" + messageId + "\"}"))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    private static Message message(UUID conversationId, UUID senderId, String content, OffsetDateTime createdAt) {
        return new Message().id(UUID.randomUUID()).conversationId(conversationId).senderId(senderId)
                .content(content).createdAt(createdAt);
    }
}
