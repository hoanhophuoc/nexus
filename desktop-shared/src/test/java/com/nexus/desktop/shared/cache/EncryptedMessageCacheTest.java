package com.nexus.desktop.shared.cache;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
        UUID sender = UUID.randomUUID();
        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            cache.upsert(new CachedMessage(matchId, conversation, sender, "Alice", "private phrase",
                    Instant.now()));
            cache.upsert(new CachedMessage(UUID.randomUUID(), conversation, UUID.randomUUID(), "Bob",
                    "unrelated", Instant.now()));
        }
        assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("private phrase");

        try (var reopened = new EncryptedMessageCache(file, owner, key)) {
            assertThat(reopened.search("PRIVATE phrase")).singleElement().satisfies(result -> {
                assertThat(result.messageId()).isEqualTo(matchId);
                // A round trip keeps identity and display name apart.
                assertThat(result.senderId()).isEqualTo(sender);
                assertThat(result.senderName()).isEqualTo("Alice");
            });
        }
    }

    @Test
    void wrongKeyAndDifferentAccountCannotReadTheCache() throws Exception {
        Path file = Files.createTempDirectory("encrypted-history-owner").resolve("history.bin");
        UUID owner = UUID.randomUUID();
        try (var cache = new EncryptedMessageCache(file, owner, key())) {
            cache.upsert(new CachedMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Alice",
                    "secret", Instant.now()));
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
            cache.upsert(new CachedMessage(hidden, conversation, UUID.randomUUID(), "Alice", "needle hidden",
                    Instant.now()));
            cache.upsert(new CachedMessage(UUID.randomUUID(), conversation, UUID.randomUUID(), "Alice",
                    "needle visible", Instant.now()));
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

    /**
     * Format 3 stored one field per message: the sender id, or a group member name once the sync knew it.
     * Existing caches must keep opening, with the id recovered as identity and the name kept as display.
     */
    @Test
    void deletingForEveryoneClearsThePreviewAndSurvivesRestart() throws Exception {
        Path file = Files.createTempDirectory("encrypted-history").resolve("history.bin");
        UUID owner = UUID.randomUUID();
        SecretKey key = key();
        UUID messageId = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        Instant deletedAt = Instant.parse("2026-11-16T08:00:00Z");
        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            cache.upsert(new CachedMessage(messageId, conversation, sender, "Alice", "the words", Instant.now()));
            cache.markDeletedForEveryone(messageId, deletedAt);
            assertThat(cache.search("the words")).as("a deleted-for-everyone message leaves search").isEmpty();
        }

        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            assertThat(cache.search("the words")).as("the deletion was persisted").isEmpty();
            assertThat(cache.messagesInConversation(conversation))
                    .singleElement()
                    .satisfies(message -> {
                        assertThat(message.deletedForEveryoneAt()).isEqualTo(deletedAt);
                        assertThat(message.content()).isEmpty();
                    });
        }
    }

    @Test
    void refusesACacheWrittenByAnUnknownFormat() throws Exception {
        Path file = Files.createTempDirectory("encrypted-history").resolve("history.bin");
        UUID owner = UUID.randomUUID();
        SecretKey key = key();
        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            cache.upsert(new CachedMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "Alice", "hello", Instant.now()));
        }
        byte[] envelope = Files.readAllBytes(file);
        envelope[4] = 99;
        Files.write(file, envelope);

        assertThatThrownBy(() -> new EncryptedMessageCache(file, owner, key))
                .as("a cache from a future format is refused rather than misread")
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Unsupported message cache format");
    }

    @Test
    void readsFormat3CachesAndSplitsIdentityFromDisplayName() throws Exception {
        Path file = Files.createTempDirectory("encrypted-format3").resolve("history.bin");
        UUID owner = UUID.randomUUID();
        SecretKey key = key();
        UUID conversation = UUID.randomUUID();
        UUID byId = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID named = UUID.randomUUID();

        ByteArrayOutputStream records = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(records)) {
            output.writeInt(2);
            writeFormat3Message(output, byId, conversation, sender.toString(), "stored as an id",
                    Instant.parse("2026-11-07T10:00:00Z"));
            writeFormat3Message(output, named, conversation, "Bob", "stored as a name",
                    Instant.parse("2026-11-07T10:00:01Z"));
            output.writeInt(0);
            output.writeInt(0);
        }

        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(ByteBuffer.allocate(4 + 1 + 16).putInt(0x43484331).put((byte) 3)
                .putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits()).array());
        byte[] ciphertext = cipher.doFinal(records.toByteArray());
        ByteBuffer envelope = ByteBuffer.allocate(4 + 1 + nonce.length + ciphertext.length);
        envelope.putInt(0x43484331).put((byte) 3).put(nonce).put(ciphertext);
        Files.createDirectories(file.getParent());
        Files.write(file, envelope.array());

        try (var cache = new EncryptedMessageCache(file, owner, key)) {
            Map<UUID, CachedMessage> byMessage = cache.messagesInConversation(conversation).stream()
                    .collect(Collectors.toMap(CachedMessage::id, message -> message));
            assertThat(byMessage).hasSize(2);
            assertThat(byMessage.get(byId).senderId()).isEqualTo(sender);
            assertThat(byMessage.get(byId).senderName()).isEmpty();
            assertThat(byMessage.get(named).senderId()).isNull();
            assertThat(byMessage.get(named).senderName()).isEqualTo("Bob");
        }
    }

    private static void writeFormat3Message(DataOutputStream output, UUID id, UUID conversation,
                                            String storedField, String content, Instant createdAt)
            throws Exception {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
        output.writeLong(conversation.getMostSignificantBits());
        output.writeLong(conversation.getLeastSignificantBits());
        output.writeUTF(storedField);
        output.writeUTF(content);
        output.writeLong(createdAt.getEpochSecond());
        output.writeInt(createdAt.getNano());
        output.writeBoolean(false);
        output.writeBoolean(false);
    }
}
