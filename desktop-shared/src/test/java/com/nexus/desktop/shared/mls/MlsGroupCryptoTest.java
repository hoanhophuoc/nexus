package com.nexus.desktop.shared.mls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlsGroupCryptoTest {
    private static final UUID GROUP = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void twoMembersExchangeEncryptedMessagesAndRejectTamperingAndOutsiders() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());

        MlsApplicationMessage hello = MlsGroupCrypto.encrypt(creation.state(), alice, bytes("hello"));
        assertThat(decrypt(bobState, bob, hello)).isEqualTo("hello");

        MlsApplicationMessage reply = MlsGroupCrypto.encrypt(bobState, bob, bytes("hi alice"));
        assertThat(decrypt(creation.state(), alice, reply)).isEqualTo("hi alice");

        byte[] tampered = hello.encode();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> MlsGroupCrypto.decrypt(bobState, bob, GROUP, tampered))
                .isInstanceOf(MlsException.class);

        MlsIdentity outsider = identity("outsider");
        assertThatThrownBy(() -> MlsGroupCrypto.decrypt(bobState, outsider, GROUP, hello.encode()))
                .isInstanceOf(MlsException.class);
        assertThatThrownBy(() -> MlsGroupCrypto.encrypt(bobState, outsider, bytes("intruder")))
                .isInstanceOf(MlsException.class);
    }

    @Test
    void addAndRemoveMembersRotateTheEpochAndCutOffRemovedMembers() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        MlsIdentity charlie = identity("charlie");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());

        var add = MlsGroupCrypto.commit(creation.state(), alice,
                List.of(MlsKeyPackage.create(charlie)), List.of());
        assertThat(add.state().epoch()).isEqualTo(2);
        MlsGroupState bobAfterAdd = MlsGroupCrypto.applyCommit(bobState, GROUP, bob, add.commit().encode());
        MlsGroupState charlieState = MlsGroupCrypto.applyCommit(null, GROUP, charlie, add.commit().encode());
        assertThat(charlieState.members()).containsKeys(alice.userId(), bob.userId(), charlie.userId());

        MlsApplicationMessage hello = MlsGroupCrypto.encrypt(add.state(), alice, bytes("all three"));
        assertThat(decrypt(bobAfterAdd, bob, hello)).isEqualTo("all three");
        assertThat(decrypt(charlieState, charlie, hello)).isEqualTo("all three");

        var remove = MlsGroupCrypto.commit(add.state(), alice, List.of(), List.of(bob.userId()));
        assertThat(remove.state().epoch()).isEqualTo(3);
        MlsGroupState charlieAfterRemove = MlsGroupCrypto.applyCommit(charlieState, GROUP, charlie,
                remove.commit().encode());
        assertThat(charlieAfterRemove.members()).doesNotContainKey(bob.userId());

        assertThatThrownBy(() -> MlsGroupCrypto.applyCommit(bobAfterAdd, GROUP, bob, remove.commit().encode()))
                .isInstanceOf(MlsException.class);

        MlsApplicationMessage afterRemoval = MlsGroupCrypto.encrypt(remove.state(), alice, bytes("secret"));
        assertThatThrownBy(() -> MlsGroupCrypto.decrypt(bobAfterAdd, bob, GROUP, afterRemoval.encode()))
                .isInstanceOf(MlsException.class);
        assertThat(decrypt(charlieAfterRemove, charlie, afterRemoval)).isEqualTo("secret");

        assertThatThrownBy(() -> MlsGroupCrypto.decrypt(charlieAfterRemove, charlie, GROUP, hello.encode()))
                .isInstanceOf(MlsException.class);
    }

    @Test
    void staleReplayedAndTamperedCommitsAreRejected() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        MlsIdentity charlie = identity("charlie");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());

        var add = MlsGroupCrypto.commit(creation.state(), alice,
                List.of(MlsKeyPackage.create(charlie)), List.of());
        MlsGroupState bobAfterAdd = MlsGroupCrypto.applyCommit(bobState, GROUP, bob, add.commit().encode());

        assertThatThrownBy(() -> MlsGroupCrypto.applyCommit(bobAfterAdd, GROUP, bob, creation.commit().encode()))
                .isInstanceOf(MlsException.class);

        byte[] tampered = add.commit().encode();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> MlsGroupCrypto.applyCommit(null, GROUP, charlie, tampered))
                .isInstanceOf(MlsException.class);
    }

    @Test
    void recoveryPackageRestoresIdentityAndGroupState() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());
        MlsApplicationMessage afterRecovery = MlsGroupCrypto.encrypt(creation.state(), alice, bytes("recovered"));

        String code = MlsStateStore.newRecoveryCode();
        byte[] packageBytes = MlsStateStore.encryptRecovery(code, bob, List.of(bobState));
        MlsStateStore.Snapshot restored = MlsStateStore.restoreRecovery(code, packageBytes);

        assertThat(restored.identity().userId()).isEqualTo(bob.userId());
        assertThat(restored.identity().deviceId()).isEqualTo(bob.deviceId());
        assertThat(restored.groups()).hasSize(1);
        assertThat(decrypt(restored.groups().getFirst(), restored.identity(), afterRecovery))
                .isEqualTo("recovered");

        assertThatThrownBy(() -> MlsStateStore.restoreRecovery(MlsStateStore.newRecoveryCode(), packageBytes))
                .isInstanceOf(MlsException.class);
        byte[] tampered = packageBytes.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> MlsStateStore.restoreRecovery(code, tampered))
                .isInstanceOf(MlsException.class);
    }

    @Test
    void encryptedStatePersistsAcrossRestart(@TempDir Path tempDir) throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());
        char[] password = "correct horse battery".toCharArray();
        Path file = tempDir.resolve("bob.mls");

        MlsGroupState loaded = saveAndLoad(file, bob, bobState, password);
        MlsApplicationMessage afterRestart = MlsGroupCrypto.encrypt(creation.state(), alice, bytes("after restart"));
        assertThat(decrypt(loaded, bob, afterRestart)).isEqualTo("after restart");

        assertThatThrownBy(() -> MlsStateStore.load(file, bob.userId(), "wrong password".toCharArray()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void duplicateApplicationMessagesAreRejected() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());
        MlsApplicationMessage message = MlsGroupCrypto.encrypt(creation.state(), alice, bytes("once"));

        assertThat(decrypt(bobState, bob, message)).isEqualTo("once");
        assertThatThrownBy(() -> MlsGroupCrypto.decrypt(bobState, bob, GROUP, message.encode()))
                .isInstanceOf(MlsException.class);
    }

    @Test
    void messageSenderSignaturePreventsImpersonation() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, bob, creation.commit().encode());

        UUID messageId = UUID.randomUUID();
        byte[] key = MlsCodec.hkdf(bobState.secret(), MlsCodec.uuidBytes(GROUP),
                MlsCodec.label("chat-mls-app-key-v1", GROUP, bobState.epoch(), alice.userId(), messageId), 32);
        byte[] nonce = MlsCodec.random(12);
        byte[] aad = MlsCodec.label("chat-mls-app-aad-v1", GROUP, bobState.epoch(), alice.userId(), messageId);
        byte[] ciphertext = MlsCodec.gcm(true, key, nonce, aad, bytes("forged"));
        MlsApplicationMessage draft = new MlsApplicationMessage(GROUP, bobState.epoch(), alice.userId(),
                messageId, nonce, ciphertext, new byte[64]);
        MlsApplicationMessage forged = new MlsApplicationMessage(GROUP, bobState.epoch(), alice.userId(),
                messageId, nonce, ciphertext, bob.sign(draft.signedPayload()));

        assertThatThrownBy(() -> decrypt(bobState, bob, forged)).isInstanceOf(MlsException.class);
    }

    @Test
    void joiningRequiresTheExpectedJoinEpoch() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));

        assertThatThrownBy(() -> MlsGroupCrypto.applyCommit(null, GROUP, 2, bob, creation.commit().encode()))
                .isInstanceOf(MlsException.class);
        MlsGroupState bobState = MlsGroupCrypto.applyCommit(null, GROUP, 1, bob, creation.commit().encode());
        assertThat(bobState.epoch()).isEqualTo(1);
    }

    @Test
    void keyPackageTamperingAndExpiryAreRejected() throws Exception {
        MlsIdentity bob = identity("bob");
        MlsKeyPackage keyPackage = MlsKeyPackage.create(bob);
        keyPackage.verify(Instant.now());

        byte[] encoded = keyPackage.encode();
        encoded[encoded.length - 1] ^= 1;
        MlsKeyPackage tampered = MlsKeyPackage.decode(encoded);
        assertThatThrownBy(() -> tampered.verify(Instant.now())).isInstanceOf(MlsException.class);

        MlsKeyPackage expired = MlsKeyPackage.create(bob, Instant.now().minus(Duration.ofDays(10)),
                Duration.ofDays(1));
        assertThatThrownBy(() -> expired.verify(Instant.now())).isInstanceOf(MlsException.class);
    }

    private static MlsGroupState saveAndLoad(Path file, MlsIdentity identity, MlsGroupState state, char[] password)
            throws Exception {
        MlsStateStore.save(file, identity.userId(), password, identity, List.of(state));
        return MlsStateStore.load(file, identity.userId(), password).orElseThrow().groups().getFirst();
    }

    private static String decrypt(MlsGroupState state, MlsIdentity identity, MlsApplicationMessage message)
            throws MlsException {
        return new String(MlsGroupCrypto.decrypt(state, identity, GROUP, message.encode()), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static MlsIdentity identity(String name) throws MlsException {
        return MlsIdentity.generate(UUID.nameUUIDFromBytes(bytes(name)), name + "-device");
    }
}
