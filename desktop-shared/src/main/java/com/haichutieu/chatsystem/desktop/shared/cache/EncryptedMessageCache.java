package com.haichutieu.chatsystem.desktop.shared.cache;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores a single account's synchronized message history as authenticated ciphertext. */
public final class EncryptedMessageCache implements AutoCloseable {
    private static final int MAGIC = 0x43484331;
    private static final byte VERSION = 3;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final UUID ownerId;
    private final SecretKey key;
    private final Map<UUID, CachedMessage> messages = new HashMap<>();
    private final Map<UUID, PendingMessage> pendingMessages = new HashMap<>();
    private final Map<UUID, String> historyCursors = new HashMap<>();
    private final Set<UUID> hiddenForMe = new HashSet<>();
    private long nextQueueOrder;
    private boolean dirty;

    public EncryptedMessageCache(Path file, UUID ownerId, SecretKey key) throws IOException {
        if (file == null || ownerId == null || key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("A file, account ID, and AES key are required.");
        }
        this.file = file.toAbsolutePath();
        this.ownerId = ownerId;
        this.key = key;
        if (Files.exists(this.file)) read();
    }

    public synchronized void upsert(CachedMessage message) throws IOException {
        upsertAll(List.of(message));
    }

    public synchronized void upsertAll(Collection<CachedMessage> updates) throws IOException {
        for (CachedMessage message : updates) {
            messages.put(message.id(), message);
        }
        if (updates.isEmpty()) return;
        dirty = true;
        persist();
    }

    public synchronized void hideForMe(UUID messageId) throws IOException {
        if (messages.containsKey(messageId)) {
            hiddenForMe.add(messageId);
            dirty = true;
            persist();
        }
    }

    public synchronized PendingMessage enqueue(UUID conversationId, String content) throws IOException {
        if (conversationId == null || content == null || content.isBlank() || content.length() > 10_000) {
            throw new IllegalArgumentException("Pending message must contain 1 to 10,000 characters and a conversation.");
        }
        PendingMessage pending = new PendingMessage(UUID.randomUUID(), conversationId, UUID.randomUUID().toString(),
                content, Instant.now(), nextQueueOrder++);
        pendingMessages.put(pending.localId(), pending);
        dirty = true;
        persist();
        return pending;
    }

    public synchronized List<PendingMessage> pendingMessages() {
        return pendingMessages.values().stream().sorted(Comparator.comparingLong(PendingMessage::queueOrder)).toList();
    }

    public synchronized void completePending(UUID localId, CachedMessage serverMessage) throws IOException {
        if (pendingMessages.remove(localId) != null) {
            messages.put(serverMessage.id(), serverMessage);
            dirty = true;
            persist();
        }
    }

    public synchronized void markDeletedForEveryone(UUID messageId, Instant deletedAt) throws IOException {
        CachedMessage message = messages.get(messageId);
        if (message != null && message.deletedForEveryoneAt() == null) {
            messages.put(messageId, new CachedMessage(message.id(), message.conversationId(), message.senderName(),
                    "", message.createdAt(), deletedAt));
            dirty = true;
            persist();
        }
    }

    public synchronized void clearConversation(UUID conversationId) throws IOException {
        boolean changed = messages.values().removeIf(message -> message.conversationId().equals(conversationId));
        changed |= hiddenForMe.removeIf(messageId -> !messages.containsKey(messageId));
        changed |= pendingMessages.values().removeIf(message -> message.conversationId().equals(conversationId));
        changed |= historyCursors.remove(conversationId) != null;
        if (changed) {
            dirty = true;
            persist();
        }
    }

    public synchronized List<MessageSearchResult> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return messages.values().stream()
                .filter(message -> !hiddenForMe.contains(message.id()))
                .filter(message -> message.deletedForEveryoneAt() == null)
                .filter(message -> message.content().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(CachedMessage::createdAt).reversed())
                .map(message -> new MessageSearchResult(message.conversationId(), message.id(), message.senderName(),
                        message.content(), message.createdAt()))
                .toList();
    }

    public synchronized boolean hasMessage(UUID messageId) {
        return messages.containsKey(messageId);
    }

    public synchronized List<CachedMessage> messagesInConversation(UUID conversationId) {
        return messages.values().stream().filter(message -> message.conversationId().equals(conversationId))
                .filter(message -> !hiddenForMe.contains(message.id()))
                .sorted(Comparator.comparing(CachedMessage::createdAt)).toList();
    }

    public synchronized List<PendingMessage> pendingInConversation(UUID conversationId) {
        return pendingMessages.values().stream().filter(message -> message.conversationId().equals(conversationId))
                .sorted(Comparator.comparingLong(PendingMessage::queueOrder)).toList();
    }

    public synchronized String historyCursor(UUID conversationId) {
        return historyCursors.get(conversationId);
    }

    public synchronized void syncPage(UUID conversationId, Collection<CachedMessage> updates, String cursor)
            throws IOException {
        for (CachedMessage message : updates) messages.put(message.id(), message);
        if (cursor != null) historyCursors.put(conversationId, cursor);
        if (!updates.isEmpty() || cursor != null) {
            dirty = true;
            persist();
        }
    }

    public UUID ownerId() { return ownerId; }

    @Override
    public synchronized void close() throws IOException {
        if (dirty) persist();
    }

    private void read() throws IOException {
        byte[] envelope = Files.readAllBytes(file);
        if (envelope.length < 4 + 1 + NONCE_BYTES + 16 || envelope.length > MAX_FILE_BYTES) {
            throw new IOException("Encrypted message cache has an invalid size.");
        }
        ByteBuffer input = ByteBuffer.wrap(envelope);
        if (input.getInt() != MAGIC || input.get() != VERSION) throw new IOException("Unsupported message cache format.");
        byte[] nonce = new byte[NONCE_BYTES];
        input.get(nonce);
        byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);
        try {
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(ciphertext);
            decode(plaintext);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Message cache authentication failed.", exception);
        }
    }

    private void persist() throws IOException {
        // shortcut: rewrites and scans one encrypted snapshot; replace with an encrypted indexed store for very large histories.
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(encode());
            if (ciphertext.length + 4 + 1 + nonce.length > MAX_FILE_BYTES) {
                throw new IOException("Encrypted message cache exceeds 256 MiB.");
            }
            ByteBuffer envelope = ByteBuffer.allocate(4 + 1 + nonce.length + ciphertext.length);
            envelope.putInt(MAGIC).put(VERSION).put(nonce).put(ciphertext);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.getFileStore(parent).supportsFileAttributeView("posix")
                    ? Files.createTempFile(parent, file.getFileName().toString(), ".tmp",
                    PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))
                    : Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, envelope.array());
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            dirty = false;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not encrypt message cache.", exception);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(ByteBuffer.allocate(4 + 1 + 16).putInt(MAGIC).put(VERSION)
                .putLong(ownerId.getMostSignificantBits()).putLong(ownerId.getLeastSignificantBits()).array());
        return cipher;
    }

    private byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(messages.size());
            for (CachedMessage message : messages.values()) {
                output.writeLong(message.id().getMostSignificantBits());
                output.writeLong(message.id().getLeastSignificantBits());
                output.writeLong(message.conversationId().getMostSignificantBits());
                output.writeLong(message.conversationId().getLeastSignificantBits());
                output.writeUTF(message.senderName());
                output.writeUTF(message.content());
                output.writeLong(message.createdAt().getEpochSecond());
                output.writeInt(message.createdAt().getNano());
                output.writeBoolean(message.deletedForEveryoneAt() != null);
                if (message.deletedForEveryoneAt() != null) {
                    output.writeLong(message.deletedForEveryoneAt().getEpochSecond());
                    output.writeInt(message.deletedForEveryoneAt().getNano());
                }
                output.writeBoolean(hiddenForMe.contains(message.id()));
            }
            output.writeInt(pendingMessages.size());
            for (PendingMessage message : pendingMessages.values()) {
                output.writeLong(message.localId().getMostSignificantBits());
                output.writeLong(message.localId().getLeastSignificantBits());
                output.writeLong(message.conversationId().getMostSignificantBits());
                output.writeLong(message.conversationId().getLeastSignificantBits());
                output.writeUTF(message.idempotencyKey());
                output.writeUTF(message.content());
                output.writeLong(message.createdAt().getEpochSecond());
                output.writeInt(message.createdAt().getNano());
                output.writeLong(message.queueOrder());
            }
            output.writeInt(historyCursors.size());
            for (Map.Entry<UUID, String> cursor : historyCursors.entrySet()) {
                output.writeLong(cursor.getKey().getMostSignificantBits());
                output.writeLong(cursor.getKey().getLeastSignificantBits());
                output.writeUTF(cursor.getValue());
            }
        }
        return bytes.toByteArray();
    }

    private void decode(byte[] plaintext) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            int count = input.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("Message cache record count is invalid.");
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(input.readLong(), input.readLong());
                UUID conversationId = new UUID(input.readLong(), input.readLong());
                String senderName = input.readUTF();
                String content = input.readUTF();
                Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
                Instant deletedAt = input.readBoolean() ? Instant.ofEpochSecond(input.readLong(), input.readInt()) : null;
                messages.put(id, new CachedMessage(id, conversationId, senderName, content, createdAt, deletedAt));
                if (input.readBoolean()) hiddenForMe.add(id);
            }
            int pendingCount = input.readInt();
            if (pendingCount < 0 || pendingCount > 1_000_000) {
                throw new IOException("Pending message count is invalid.");
            }
            for (int i = 0; i < pendingCount; i++) {
                UUID localId = new UUID(input.readLong(), input.readLong());
                UUID conversationId = new UUID(input.readLong(), input.readLong());
                String idempotencyKey = input.readUTF();
                String content = input.readUTF();
                Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
                long order = input.readLong();
                pendingMessages.put(localId, new PendingMessage(localId, conversationId, idempotencyKey,
                        content, createdAt, order));
                nextQueueOrder = Math.max(nextQueueOrder, order + 1);
            }
            int cursorCount = input.readInt();
            if (cursorCount < 0 || cursorCount > 100_000) throw new IOException("History cursor count is invalid.");
            for (int i = 0; i < cursorCount; i++) {
                UUID conversationId = new UUID(input.readLong(), input.readLong());
                historyCursors.put(conversationId, input.readUTF());
            }
            if (input.available() != 0) throw new IOException("Message cache has trailing data.");
        }
    }
}
