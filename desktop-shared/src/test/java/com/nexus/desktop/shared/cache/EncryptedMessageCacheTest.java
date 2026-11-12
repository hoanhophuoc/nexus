package com.nexus.desktop.shared.cache;

import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedMessageCacheTest {
    private static SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    @Test
    void persistsEncryptedHistoryAndSearchesItLocally() throws Exception {
        Path file = Files.createTempDirectory("encrypted-history").resolve("history.bin");
        UUID owner = UUID.randomUUID();
        SecretKey key = key();
        UUID conversation = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            cache.upsert(new CachedMessage(matchId, conversation, "Alice", "private phrase", Instant.now()));
            cache.upsert(new CachedMessage(UUID.randomUUID(), conversation, "Bob", "unrelated", Instant.now()));
        }
        assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("private phrase");

        try (var reopened = new EncryptedMessageCache(file, owner, key)) {
            assertThat(reopened.search("PRIVATE phrase")).singleElement()
                    .satisfies(result -> assertThat(result.messageId()).isEqualTo(matchId));
        }
    }

    @Test
    void wrongKeyAndDifferentAccountCannotReadTheCache() throws Exception {
        Path file = Files.createTempDirectory("encrypted-history-owner").resolve("history.bin");
        UUID owner = UUID.randomUUID();
        try (var cache = new EncryptedMessageCache(file, owner, key())) {
            cache.upsert(new CachedMessage(UUID.randomUUID(), UUID.randomUUID(), "Alice", "secret", Instant.now()));
        }

        assertThatThrownBy(() -> new EncryptedMessageCache(file, owner, key()))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new EncryptedMessageCache(file, UUID.randomUUID(), key()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void localHideAndClearDoNotReturnHiddenHistoryInSearch() throws Exception {
        Path file = Files.createTempDirectory("encrypted-history-delete").resolve("history.bin");
        UUID conversation = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        try (var cache = new EncryptedMessageCache(file, UUID.randomUUID(), key())) {
            cache.upsert(new CachedMessage(hidden, conversation, "Alice", "needle hidden", Instant.now()));
            cache.upsert(new CachedMessage(UUID.randomUUID(), conversation, "Alice", "needle visible", Instant.now()));
            cache.hideForMe(hidden);
            assertThat(cache.search("needle")).singleElement()
                    .satisfies(result -> assertThat(result.preview()).isEqualTo("needle visible"));
            cache.clearConversation(conversation);
            assertThat(cache.search("needle")).isEmpty();
        }
    }

    @Test
    void pendingMessagesSurviveRestartInTheSameOrderWithStableIdempotencyKeys() throws Exception {
        Path file = Files.createTempDirectory("encrypted-pending").resolve("cache");
        UUID owner = UUID.randomUUID();
        SecretKey key = key();
        UUID conversationId = UUID.randomUUID();
        String firstKey;
        String cursor = UUID.randomUUID().toString();
        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            firstKey = cache.enqueue(conversationId, "first").idempotencyKey();
            cache.enqueue(conversationId, "second");
            cache.syncPage(conversationId, java.util.List.of(), cursor);
        }

        try (var reopened = new EncryptedMessageCache(file, owner, key)) {
            assertThat(reopened.pendingMessages()).extracting(PendingMessage::content)
                    .containsExactly("first", "second");
            assertThat(reopened.pendingMessages().getFirst().idempotencyKey()).isEqualTo(firstKey);
            assertThat(reopened.historyCursor(conversationId)).isEqualTo(cursor);
            reopened.clearConversation(conversationId);
            assertThat(reopened.historyCursor(conversationId)).isNull();
        }
    }
}
