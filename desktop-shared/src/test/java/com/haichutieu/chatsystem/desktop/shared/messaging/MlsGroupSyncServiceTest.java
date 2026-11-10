package com.haichutieu.chatsystem.desktop.shared.messaging;

import com.haichutieu.chatsystem.desktop.shared.cache.CachedMessage;
import com.haichutieu.chatsystem.desktop.shared.cache.EncryptedMessageCache;
import com.haichutieu.chatsystem.sdk.ApiException;
import com.haichutieu.chatsystem.sdk.api.DefaultApi;
import com.haichutieu.chatsystem.sdk.model.AddGroupMemberRequest;
import com.haichutieu.chatsystem.sdk.model.Conversation;
import com.haichutieu.chatsystem.sdk.model.CreateGroup;
import com.haichutieu.chatsystem.sdk.model.GroupMember;
import com.haichutieu.chatsystem.sdk.model.GroupMemberPage;
import com.haichutieu.chatsystem.sdk.model.MlsMessage;
import com.haichutieu.chatsystem.sdk.model.MlsMessagePage;
import com.haichutieu.chatsystem.sdk.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the client-side glue without a real HTTP server: one in-memory group
 * authority, three independent device identities, and the same ciphertext log shape
 * the Spring server exposes.
 */
class MlsGroupSyncServiceTest {
    @TempDir
    Path directory;

    @Test
    void createsJoinsExchangesAndRecoversFromASnapshot() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID charlieId = UUID.randomUUID();
        SecretKey key = key((byte) 9);
        FakeServer server = new FakeServer();

        try (EncryptedMessageCache aliceCache = cache("alice", aliceId);
             EncryptedMessageCache bobCache = cache("bob", bobId);
             EncryptedMessageCache charlieCache = cache("charlie", charlieId)) {
            MlsGroupSyncService alice = service(server, aliceId, aliceCache, "alice");
            MlsGroupSyncService bob = service(server, bobId, bobCache, "bob");
            MlsGroupSyncService charlie = service(server, charlieId, charlieCache, "charlie");
            alice.publishKeyPackage();
            bob.publishKeyPackage();
            charlie.publishKeyPackage();

            Conversation created = alice.createGroup("project team", List.of(bobId));
            UUID groupId = created.getId();
            assertThat(created.getName()).isEqualTo("project team");

            bob.syncGroup(groupId);
            assertThat(bob.hasState(groupId)).isTrue();

            alice.sendGroupMessage(groupId, "hello bob");
            bob.syncGroup(groupId);
            assertThat(contents(bobCache, groupId)).contains("hello bob");

            bob.sendGroupMessage(groupId, "hi alice");
            alice.syncGroup(groupId);
            assertThat(contents(aliceCache, groupId)).contains("hi alice");

            alice.addMember(groupId, charlieId);
            bob.syncGroup(groupId);
            charlie.syncGroup(groupId);
            assertThat(charlie.hasState(groupId)).isTrue();

            alice.sendGroupMessage(groupId, "welcome charlie");
            charlie.syncGroup(groupId);
            assertThat(contents(charlieCache, groupId)).contains("welcome charlie");

            alice.removeMember(groupId, bobId);
            charlie.syncGroup(groupId);
            alice.sendGroupMessage(groupId, "after removal");
            charlie.syncGroup(groupId);
            assertThat(contents(charlieCache, groupId)).contains("after removal");

            try (EncryptedMessageCache recoveredCache = cache("charlie-recovered", charlieId)) {
                MlsGroupSyncService recovered = service(server, charlieId, recoveredCache, "charlie-recovered");
                assertThat(recovered.restoreRecovery(groupId)).isTrue();
                assertThat(recovered.hasState(groupId)).isTrue();

                alice.sendGroupMessage(groupId, "after restore");
                recovered.syncGroup(groupId);

                assertThat(contents(recoveredCache, groupId)).contains("after restore");
            }
        }
    }

    @Test
    void routesRealtimeCommitAndMessageEventsToTheRightGroup() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID charlieId = UUID.randomUUID();
        FakeServer server = new FakeServer();

        try (EncryptedMessageCache aliceCache = cache("rt-alice", aliceId);
             EncryptedMessageCache bobCache = cache("rt-bob", bobId);
             EncryptedMessageCache charlieCache = cache("rt-charlie", charlieId)) {
            MlsGroupSyncService alice = service(server, aliceId, aliceCache, "rt-alice");
            MlsGroupSyncService bob = service(server, bobId, bobCache, "rt-bob");
            MlsGroupSyncService charlie = service(server, charlieId, charlieCache, "rt-charlie");
            alice.publishKeyPackage();
            bob.publishKeyPackage();
            charlie.publishKeyPackage();

            UUID groupId = alice.createGroup("realtime", List.of(bobId)).getId();

            // A new member can join directly from the realtime commit event, without opening the group first.
            assertThat(bob.handleRealtimeEvent(event("MlsCommit", groupId))).isTrue();
            assertThat(bob.hasState(groupId)).isTrue();

            alice.sendGroupMessage(groupId, "hello realtime");
            assertThat(bob.handleRealtimeEvent(event("MlsMessage", groupId))).isTrue();
            assertThat(contents(bobCache, groupId)).contains("hello realtime");

            alice.addMember(groupId, charlieId);
            assertThat(bob.handleRealtimeEvent(event("MlsCommit", groupId))).isTrue();
            alice.sendGroupMessage(groupId, "after add");
            assertThat(bob.handleRealtimeEvent(event("MlsMessage", groupId))).isTrue();
            assertThat(contents(bobCache, groupId)).contains("after add");

            assertThat(bob.handleRealtimeEvent("""
                    {"type":"MessageCreated","conversationId":"%s"}
                    """.formatted(groupId))).isFalse();
            assertThat(bob.handleRealtimeEvent("not-json")).isFalse();
        }
    }

    private static String event(String type, UUID groupId) {
        return """
                {"type":"%s","conversationId":"%s"}
                """.formatted(type, groupId);
    }

    private MlsGroupSyncService service(FakeServer server, UUID userId, EncryptedMessageCache cache,
                                        String name) throws Exception {
        return new MlsGroupSyncService(new FakeApi(server, userId),
                cache, directory.resolve(name + "-mls.bin"), userId, key((byte) 9));
    }

    private EncryptedMessageCache cache(String name, UUID userId) throws IOException {
        return new EncryptedMessageCache(directory.resolve(name + "-history.bin"), userId, key((byte) 9));
    }

    private static List<String> contents(EncryptedMessageCache cache, UUID groupId) {
        return cache.messagesInConversation(groupId).stream().map(CachedMessage::content).toList();
    }

    private static SecretKey key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "AES");
    }

    /** Minimal authorization + ciphertext log, matching the server's observable rules. */
    private static final class FakeServer {
        private final Map<UUID, byte[]> keyPackages = new HashMap<>();
        private final Map<UUID, Group> groups = new LinkedHashMap<>();

        private Conversation createGroup(UUID actor, CreateGroup request) throws ApiException {
            UUID groupId = UUID.randomUUID();
            Group group = new Group(groupId, request.getName().strip());
            group.members.put(actor, new Member(user(actor), GroupMember.RoleEnum.ADMIN, 0));
            for (UUID invitee : request.getInvitedUserIds()) {
                group.members.put(invitee, new Member(user(invitee), GroupMember.RoleEnum.MEMBER, 0));
            }
            groups.put(groupId, group);
            return new Conversation().id(groupId).name(group.name).type(Conversation.TypeEnum.GROUP)
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC));
        }

        private void addMember(UUID actor, UUID groupId, UUID userId) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            if (group.members.containsKey(userId)) throw new ApiException(409, "Already a member.");
            group.members.put(userId, new Member(user(userId), GroupMember.RoleEnum.MEMBER, group.epoch + 1));
        }

        private void removeMember(UUID actor, UUID groupId, UUID userId) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            if (group.members.remove(userId) == null) throw new ApiException(404, "Not a member.");
        }

        private void submitCommit(UUID actor, UUID groupId, long expectedEpoch, byte[] commit) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            if (expectedEpoch != group.epoch) throw new ApiException(409, "Stale epoch.");
            group.epoch++;
            group.messages.add(message(groupId, group.epoch, MlsMessage.KindEnum.COMMIT, commit));
        }

        private MlsMessage send(UUID actor, UUID groupId, long epoch, String idempotencyKey,
                                byte[] ciphertext) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            if (epoch != group.epoch) throw new ApiException(409, "Stale epoch.");
            String key = actor + ":" + idempotencyKey;
            MlsMessage previous = group.idempotency.get(key);
            if (previous != null) {
                if (!Arrays.equals(previous.getCiphertext(), ciphertext)) throw new ApiException(409, "Conflict.");
                return previous;
            }
            MlsMessage message = message(groupId, group.epoch, MlsMessage.KindEnum.APPLICATION, ciphertext);
            group.idempotency.put(key, message);
            group.messages.add(message);
            return message;
        }

        private MlsMessagePage messages(UUID actor, UUID groupId) throws ApiException {
            Group group = group(groupId);
            Member member = requireMember(group, actor);
            return new MlsMessagePage().items(group.messages.stream()
                    .filter(message -> message.getEpoch() >= member.joinedEpoch)
                    .toList()).nextCursor(null);
        }

        private GroupMemberPage members(UUID actor, UUID groupId) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            List<GroupMember> members = group.members.entrySet().stream()
                    .map(entry -> new GroupMember().user(entry.getValue().user)
                            .role(entry.getValue().role)
                            .joinedEpoch(Math.toIntExact(entry.getValue().joinedEpoch)))
                    .toList();
            return new GroupMemberPage().items(members).nextCursor(null);
        }

        private void storeRecovery(UUID actor, UUID groupId, byte[] bytes) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            group.recovery.put(actor, bytes.clone());
        }

        private byte[] recovery(UUID actor, UUID groupId) throws ApiException {
            Group group = group(groupId);
            requireMember(group, actor);
            byte[] bytes = group.recovery.get(actor);
            if (bytes == null) throw new ApiException(404, "No recovery package.");
            return bytes;
        }

        private Group group(UUID groupId) throws ApiException {
            Group group = groups.get(groupId);
            if (group == null) throw new ApiException(404, "No group.");
            return group;
        }

        private Member requireMember(Group group, UUID userId) throws ApiException {
            Member member = group.members.get(userId);
            if (member == null) throw new ApiException(404, "Not a member.");
            return member;
        }

        private static User user(UUID id) {
            String suffix = id.toString().substring(0, 8);
            return new User().id(id).username("user-" + suffix).fullName("User " + suffix)
                    .presence(User.PresenceEnum.ONLINE);
        }

        private static MlsMessage message(UUID groupId, long epoch, MlsMessage.KindEnum kind, byte[] bytes) {
            return new MlsMessage().id(UUID.randomUUID()).epoch(Math.toIntExact(epoch)).kind(kind)
                    .ciphertext(bytes.clone()).createdAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    private static final class Group {
        private final UUID id;
        private final String name;
        private final Map<UUID, Member> members = new LinkedHashMap<>();
        private final List<MlsMessage> messages = new ArrayList<>();
        private final Map<String, MlsMessage> idempotency = new HashMap<>();
        private final Map<UUID, byte[]> recovery = new HashMap<>();
        private long epoch;

        private Group(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private record Member(User user, GroupMember.RoleEnum role, long joinedEpoch) { }

    private static final class FakeApi extends DefaultApi {
        private final FakeServer server;
        private final UUID actor;

        private FakeApi(FakeServer server, UUID actor) {
            this.server = server;
            this.actor = actor;
        }

        @Override
        public void publishKeyPackage(File body) throws ApiException {
            server.keyPackages.put(actor, read(body));
        }

        @Override
        public File getKeyPackage(UUID userId) throws ApiException {
            byte[] bytes = server.keyPackages.get(userId);
            if (bytes == null) throw new ApiException(404, "No key package.");
            return temporaryFile(bytes);
        }

        @Override
        public Conversation createGroup(CreateGroup createGroup) throws ApiException {
            return server.createGroup(actor, createGroup);
        }

        @Override
        public void addGroupMember(UUID groupId, AddGroupMemberRequest request) throws ApiException {
            server.addMember(actor, groupId, request.getUserId());
        }

        @Override
        public void removeGroupMember(UUID groupId, UUID userId) throws ApiException {
            server.removeMember(actor, groupId, userId);
        }

        @Override
        public void submitMlsCommit(UUID groupId, Long epoch, File body) throws ApiException {
            server.submitCommit(actor, groupId, epoch, read(body));
        }

        @Override
        public MlsMessage sendMlsApplicationMessage(UUID groupId, Long epoch, String idempotencyKey,
                                                    File body) throws ApiException {
            return server.send(actor, groupId, epoch, idempotencyKey, read(body));
        }

        @Override
        public MlsMessagePage getMlsMessages(UUID groupId, String after, Integer limit) throws ApiException {
            return server.messages(actor, groupId);
        }

        @Override
        public GroupMemberPage listGroupMembers(UUID groupId, String cursor, Integer limit) throws ApiException {
            return server.members(actor, groupId);
        }

        @Override
        public void storeEncryptedRecoveryPackage(UUID groupId, File body) throws ApiException {
            server.storeRecovery(actor, groupId, read(body));
        }

        @Override
        public File getEncryptedRecoveryPackage(UUID groupId) throws ApiException {
            return temporaryFile(server.recovery(actor, groupId));
        }

        private static byte[] read(File file) throws ApiException {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException exception) {
                throw new ApiException(500, "Could not read the test payload.");
            }
        }

        private static File temporaryFile(byte[] bytes) throws ApiException {
            try {
                Path file = Files.createTempFile("mls-test-", ".bin");
                Files.write(file, bytes);
                return file.toFile();
            } catch (IOException exception) {
                throw new ApiException(500, "Could not write the test payload.");
            }
        }
    }
}
