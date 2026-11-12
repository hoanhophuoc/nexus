package com.nexus.desktop.shared.messaging;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.cache.EncryptedMessageCache;
import com.nexus.desktop.shared.mls.MlsApplicationMessage;
import com.nexus.desktop.shared.mls.MlsCommit;
import com.nexus.desktop.shared.mls.MlsException;
import com.nexus.desktop.shared.mls.MlsGroupCrypto;
import com.nexus.desktop.shared.mls.MlsGroupState;
import com.nexus.desktop.shared.mls.MlsIdentity;
import com.nexus.desktop.shared.mls.MlsKeyPackage;
import com.nexus.desktop.shared.mls.MlsStateStore;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.api.DefaultApi;
import com.nexus.sdk.model.AddGroupMemberRequest;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.CreateGroup;
import com.nexus.sdk.model.GroupMember;
import com.nexus.sdk.model.GroupMemberPage;
import com.nexus.sdk.model.MlsMessage;
import com.nexus.sdk.model.MlsMessagePage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the local MLS-style epoch state in step with the server's opaque commit log.
 *
 * <p>The server never sees group keys. A newly invited member rejoins from the commit that
 * contains their welcome; an existing member advances one commit at a time. Recovery packages
 * contain the same encrypted snapshot that is stored on disk and are uploaded after every
 * epoch change.</p>
 */
public final class MlsGroupSyncService {
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 100;
    private static final int MAX_PLAINTEXT = 10_000;
    private static final JsonMapper EVENTS = JsonMapper.builder().build();

    private final DefaultApi api;
    private final EncryptedMessageCache cache;
    private final Path stateFile;
    private final UUID ownerId;
    private final SecretKey stateKey;
    private final String recoveryCode;
    private MlsIdentity identity;
    private final Map<UUID, MlsGroupState> groups = new LinkedHashMap<>();
    /** Created with the service so a request never adds a directory while the app is shutting down. */
    private final Path uploadDirectory;

    public MlsGroupSyncService(DefaultApi api, EncryptedMessageCache cache, Path stateFile,
                               UUID ownerId, SecretKey key) throws IOException, MlsException {
        if (api == null || cache == null || stateFile == null || ownerId == null || key == null) {
            throw new IllegalArgumentException("An API, cache, state file, account and key are required.");
        }
        this.api = api;
        this.cache = cache;
        this.stateFile = stateFile.toAbsolutePath();
        this.ownerId = ownerId;
        this.stateKey = key;
        this.recoveryCode = recoveryCode(key, ownerId);
        this.uploadDirectory = this.stateFile.getParent().resolve("uploads");
        Files.createDirectories(this.uploadDirectory);
        var snapshot = MlsStateStore.load(this.stateFile, ownerId, key);
        if (snapshot.isPresent()) {
            if (!ownerId.equals(snapshot.get().identity().userId())) {
                throw new MlsException("The local MLS identity belongs to another account.");
            }
            this.identity = snapshot.get().identity();
            for (MlsGroupState state : snapshot.get().groups()) groups.put(state.groupId(), state);
        } else {
            this.identity = MlsIdentity.generate(ownerId, "desktop-" + UUID.randomUUID());
            save();
        }
    }

    public UUID ownerId() { return ownerId; }

    public MlsIdentity identity() { return identity; }

    public String recoveryCode() { return recoveryCode; }

    public boolean hasState(UUID groupId) { return groups.containsKey(groupId); }

    /** Publishes a current signed public key package so friends can invite this account. */
    public void publishKeyPackage() throws IOException, MlsException, ApiException {
        MlsKeyPackage keyPackage = MlsKeyPackage.create(identity);
        withTemporaryFile(keyPackage.encode(), api::publishKeyPackage);
    }

    /**
     * Restores encrypted identity and group state from the server if this device has no local
     * state yet. Returns true when a recovery package was accepted.
     */
    public boolean restoreRecovery(UUID groupId) throws IOException, MlsException, ApiException {
        if (groupId == null || groups.containsKey(groupId)) return false;
        File downloaded;
        try {
            downloaded = api.getEncryptedRecoveryPackage(groupId);
        } catch (ApiException exception) {
            if (exception.getCode() == 404) return false;
            throw exception;
        }
        byte[] bytes = readDownloaded(downloaded);
        MlsStateStore.Snapshot restored = MlsStateStore.restoreRecovery(recoveryCode, bytes);
        if (!ownerId.equals(restored.identity().userId())) {
            throw new MlsException("The recovery package belongs to another account.");
        }
        boolean sameIdentity = java.util.Arrays.equals(identity.signingPublicKey().getEncoded(),
                restored.identity().signingPublicKey().getEncoded());
        if (!sameIdentity && !groups.isEmpty()) {
            throw new MlsException("The recovery package conflicts with the local MLS identity.");
        }
        if (groups.isEmpty()) identity = restored.identity();
        // Never overwrite a newer local epoch with an older snapshot from another group's package.
        for (MlsGroupState state : restored.groups()) groups.putIfAbsent(state.groupId(), state);
        save();
        publishKeyPackage();
        return true;
    }

    public Conversation createGroup(String name, List<UUID> invitedUserIds)
            throws IOException, MlsException, ApiException {
        if (name == null || name.isBlank() || name.length() > 120 || invitedUserIds == null
                || invitedUserIds.isEmpty() || invitedUserIds.size() > 100
                || new LinkedHashSet<>(invitedUserIds).size() != invitedUserIds.size()
                || invitedUserIds.contains(ownerId)) {
            throw new IllegalArgumentException("A group needs a name and distinct invited users.");
        }
        List<MlsKeyPackage> invitees = new ArrayList<>(invitedUserIds.size());
        for (UUID userId : invitedUserIds) invitees.add(fetchKeyPackage(userId));
        Conversation created = api.createGroup(new CreateGroup().name(name.strip())
                .invitedUserIds(new LinkedHashSet<>(invitedUserIds)));
        MlsGroupCrypto.Creation creation = MlsGroupCrypto.createGroup(created.getId(), identity, invitees);
        try {
            submitCommit(created.getId(), 0, creation.commit().encode());
        } catch (ApiException exception) {
            throw new MlsException("The group exists on the server but its first MLS commit failed; "
                    + "open it again to retry.", exception);
        }
        groups.put(created.getId(), creation.state());
        save();
        uploadRecovery(created.getId());
        return created;
    }

    public void addMember(UUID groupId, UUID userId) throws IOException, MlsException, ApiException {
        MlsGroupState state = requireState(groupId);
        if (state.isMember(userId)) return;
        MlsKeyPackage addition = fetchKeyPackage(userId);
        try {
            api.addGroupMember(groupId, new AddGroupMemberRequest().userId(userId));
        } catch (ApiException exception) {
            // A previous attempt may already have added the membership before its commit failed.
            if (exception.getCode() != 409) throw exception;
        }
        MlsGroupCrypto.CommitResult result = MlsGroupCrypto.commit(state, identity, List.of(addition), List.of());
        submitCommit(groupId, state.epoch(), result.commit().encode());
        groups.put(groupId, result.state());
        save();
        uploadRecovery(groupId);
    }

    public void removeMember(UUID groupId, UUID userId) throws IOException, MlsException, ApiException {
        MlsGroupState state = requireState(groupId);
        if (userId.equals(ownerId)) throw new IllegalArgumentException("Use leaveGroup for your own membership.");
        MlsGroupCrypto.CommitResult result = MlsGroupCrypto.commit(state, identity, List.of(), List.of(userId));
        submitCommit(groupId, state.epoch(), result.commit().encode());
        groups.put(groupId, result.state());
        save();
        try {
            api.removeGroupMember(groupId, userId);
        } catch (ApiException exception) {
            if (exception.getCode() != 404) throw exception;
        }
        uploadRecovery(groupId);
    }

    /** Ends this account's server membership and discards its local group keys. */
    public void leaveGroup(UUID groupId) throws IOException, ApiException {
        if (groupId == null) throw new IllegalArgumentException("A group is required.");
        api.removeGroupMember(groupId, ownerId);
        groups.remove(groupId);
        save();
    }

    public MlsApplicationMessage sendGroupMessage(UUID groupId, String text)
            throws IOException, MlsException, ApiException {
        MlsGroupState state = requireState(groupId);
        if (text == null || text.isBlank() || text.length() > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("Group messages must contain 1 to 10,000 characters.");
        }
        MlsApplicationMessage message = MlsGroupCrypto.encrypt(state, identity, text.getBytes(StandardCharsets.UTF_8));
        MlsMessage saved = withTemporaryFileResult(message.encode(), body ->
                api.sendMlsApplicationMessage(groupId, state.epoch(), UUID.randomUUID().toString(), body));
        if (saved == null) throw new IOException("The server did not return the stored group message.");
        cache.upsert(new CachedMessage(saved.getId(), groupId, identity.userId().toString(), text,
                saved.getCreatedAt().toInstant()));
        return message;
    }

    /** Processes every visible commit and application message for one group. */
    public void syncGroup(UUID groupId) throws IOException, MlsException, ApiException {
        if (groupId == null) return;
        List<MlsMessage> messages = allMessages(groupId);
        Map<UUID, String> names = memberNames(groupId);
        MlsGroupState state = groups.get(groupId);
        boolean epochChanged = false;
        long joinedEpoch = joinedEpoch(groupId);
        if (state != null && joinedEpoch > state.epoch()) {
            // This account was removed and re-added; its old epoch state cannot decrypt the new roster.
            groups.remove(groupId);
            state = null;
        }
        if (state == null) {
            for (MlsMessage message : messages) {
                if (message.getKind() != MlsMessage.KindEnum.COMMIT) continue;
                if (message.getEpoch().longValue() < joinedEpoch) continue;
                try {
                    state = MlsGroupCrypto.applyCommit(null, groupId, message.getEpoch(),
                            identity, message.getCiphertext());
                    groups.put(groupId, state);
                    save();
                    epochChanged = true;
                    break;
                } catch (MlsException ignored) {
                    // This commit may belong to another member's join; keep looking.
                }
            }
            if (state == null && messages.stream().noneMatch(message -> message.getKind() == MlsMessage.KindEnum.COMMIT)) {
                state = initializeGroup(groupId, joinedEpoch);
                epochChanged = state != null;
            }
            if (state == null) {
                throw new MlsException("Waiting for the group commit that adds this account.");
            }
        }

        messages = new ArrayList<>(messages);
        messages.sort(Comparator.comparingLong((MlsMessage message) -> message.getEpoch())
                .thenComparing(message -> message.getKind() == MlsMessage.KindEnum.COMMIT ? 0 : 1)
                .thenComparing(message -> message.getId().toString()));
        for (MlsMessage message : messages) {
            long epoch = message.getEpoch();
            if (message.getKind() == MlsMessage.KindEnum.COMMIT) {
                if (epoch <= state.epoch()) continue;
                if (epoch > state.epoch() + 1) {
                    throw new MlsException("Group history has an epoch gap; resynchronize before sending.");
                }
                state = MlsGroupCrypto.applyCommit(state, groupId, state.epoch(), identity,
                        message.getCiphertext());
                groups.put(groupId, state);
                save();
                epochChanged = true;
                continue;
            }
            if (epoch != state.epoch() || cache.hasMessage(message.getId())) continue;
            try {
                MlsApplicationMessage envelope = MlsApplicationMessage.decode(message.getCiphertext());
                byte[] plaintext = MlsGroupCrypto.decrypt(state, identity, groupId, message.getCiphertext());
                String content = new String(plaintext, StandardCharsets.UTF_8);
                if (content.isEmpty() || content.length() > MAX_PLAINTEXT) continue;
                cache.upsert(new CachedMessage(message.getId(), groupId,
                        names.getOrDefault(envelope.senderId(), envelope.senderId().toString()), content,
                        message.getCreatedAt().toInstant()));
            } catch (MlsException ignored) {
                // A stale, removed-member or tampered ciphertext must not block the rest of the log.
            }
        }
        save();
        if (epochChanged) uploadRecovery(groupId);
    }

    /** Handles realtime MLS notifications; direct-message events return false. */
    public boolean handleRealtimeEvent(String json) throws IOException, MlsException, ApiException {
        JsonNode event;
        try {
            event = EVENTS.readTree(json);
        } catch (RuntimeException exception) {
            return false;
        }
        JsonNode type = event.get("type");
        if (type == null || !type.isString()
                || (!"MlsCommit".equals(type.asText()) && !"MlsMessage".equals(type.asText()))) {
            return false;
        }
        JsonNode conversationId = event.get("conversationId");
        if (conversationId == null || !conversationId.isString()) throw new IOException("MLS event has no group.");
        syncGroup(UUID.fromString(conversationId.asText()));
        return true;
    }

    /** Recovers a group whose server row exists but whose creator never uploaded epoch 1. */
    private MlsGroupState initializeGroup(UUID groupId, long joinedEpoch) throws IOException, MlsException, ApiException {
        if (joinedEpoch != 0) return null;
        List<GroupMember> members = allMembers(groupId);
        boolean admin = members.stream().anyMatch(member -> ownerId.equals(member.getUser().getId())
                && member.getRole() == GroupMember.RoleEnum.ADMIN);
        if (!admin) return null;
        List<MlsKeyPackage> invitees = new ArrayList<>();
        for (GroupMember member : members) {
            if (!ownerId.equals(member.getUser().getId())) invitees.add(fetchKeyPackage(member.getUser().getId()));
        }
        MlsGroupCrypto.Creation creation = MlsGroupCrypto.createGroup(groupId, identity, invitees);
        try {
            submitCommit(groupId, 0, creation.commit().encode());
        } catch (ApiException conflict) {
            return null; // Another member won the race; the next sync applies their commit.
        }
        groups.put(groupId, creation.state());
        return creation.state();
    }

    private List<GroupMember> allMembers(UUID groupId) throws ApiException, IOException {
        List<GroupMember> members = new ArrayList<>();
        String cursor = null;
        do {
            GroupMemberPage page = api.listGroupMembers(groupId, cursor, 100);
            if (page == null || page.getItems() == null) break;
            members.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return members;
    }

    private MlsGroupState requireState(UUID groupId) throws MlsException {
        MlsGroupState state = groups.get(groupId);
        if (state == null) throw new MlsException("Open and synchronize the group first.");
        return state;
    }

    private MlsKeyPackage fetchKeyPackage(UUID userId) throws IOException, MlsException, ApiException {
        File downloaded = api.getKeyPackage(userId);
        MlsKeyPackage keyPackage = MlsKeyPackage.decode(readDownloaded(downloaded));
        if (!userId.equals(keyPackage.userId())) throw new MlsException("The key package belongs to another user.");
        keyPackage.verify(Instant.now());
        return keyPackage;
    }

    private List<MlsMessage> allMessages(UUID groupId) throws ApiException {
        List<MlsMessage> messages = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            MlsMessagePage found = api.getMlsMessages(groupId, cursor, PAGE_SIZE);
            if (found == null || found.getItems() == null) break;
            messages.addAll(found.getItems());
            String next = found.getNextCursor();
            if (next == null || next.equals(cursor) || found.getItems().isEmpty()) break;
            cursor = next;
        }
        return messages;
    }

    private long joinedEpoch(UUID groupId) throws ApiException, IOException {
        for (GroupMember member : allMembers(groupId)) {
            if (ownerId.equals(member.getUser().getId())) return member.getJoinedEpoch();
        }
        throw new IOException("This account is not an active group member.");
    }

    private Map<UUID, String> memberNames(UUID groupId) throws ApiException, IOException {
        Map<UUID, String> names = new LinkedHashMap<>();
        String cursor = null;
        do {
            GroupMemberPage page = api.listGroupMembers(groupId, cursor, 100);
            if (page == null || page.getItems() == null) break;
            for (GroupMember member : page.getItems()) {
                String name = member.getUser().getFullName();
                names.put(member.getUser().getId(), name == null || name.isBlank()
                        ? member.getUser().getUsername() : name);
            }
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return names;
    }

    private void submitCommit(UUID groupId, long expectedEpoch, byte[] commit)
            throws IOException, ApiException {
        withTemporaryFile(commit, body -> api.submitMlsCommit(groupId, expectedEpoch, body));
    }

    private void save() throws IOException {
        MlsStateStore.save(stateFile, ownerId, stateKey, identity, groups.values());
    }

    private void uploadRecovery(UUID groupId) {
        if (groupId == null || !groups.containsKey(groupId)) return;
        try {
            // The package contains the full snapshot, so one per-group copy is enough to restore
            // this account on a new device; later groups catch up from the server commit log.
            byte[] packageBytes = MlsStateStore.encryptRecovery(recoveryCode, identity, groups.values());
            withTemporaryFile(packageBytes, body -> api.storeEncryptedRecoveryPackage(groupId, body));
        } catch (MlsException | ApiException | IOException ignored) {
            // Recovery is best-effort; the local encrypted state remains authoritative.
        }
    }

    private static String recoveryCode(SecretKey key, UUID ownerId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("chat-mls-recovery-code-v1".getBytes(StandardCharsets.US_ASCII));
            digest.update(key.getEncoded());
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 56));
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 48));
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 40));
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 32));
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 24));
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 16));
            digest.update((byte) (ownerId.getMostSignificantBits() >>> 8));
            digest.update((byte) ownerId.getMostSignificantBits());
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 56));
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 48));
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 40));
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 32));
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 24));
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 16));
            digest.update((byte) (ownerId.getLeastSignificantBits() >>> 8));
            digest.update((byte) ownerId.getLeastSignificantBits());
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static byte[] readDownloaded(File file) throws IOException {
        if (file == null) throw new IOException("The server returned an empty file.");
        try {
            return Files.readAllBytes(file.toPath());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private void withTemporaryFile(byte[] content, FileAction action)
            throws IOException, ApiException {
        withTemporaryFileResult(content, body -> {
            action.run(body);
            return null;
        });
    }

    private <T> T withTemporaryFileResult(byte[] content, FileFunction<T> action)
            throws IOException, ApiException {
        // The payload goes next to the account state, not into the shared temp directory, so no
        // other local user can observe or race the upload file. The name is unique and the file is
        // created exclusively; it is removed as soon as the request returns.
        Path file = uploadDirectory.resolve("mls-" + UUID.randomUUID() + ".bin");
        try {
            Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return action.apply(file.toFile());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @FunctionalInterface
    private interface FileAction {
        void run(File file) throws IOException, ApiException;
    }

    @FunctionalInterface
    private interface FileFunction<T> {
        T apply(File file) throws IOException, ApiException;
    }
}
