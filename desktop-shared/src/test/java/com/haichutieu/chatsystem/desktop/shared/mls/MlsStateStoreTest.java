package com.haichutieu.chatsystem.desktop.shared.mls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlsStateStoreTest {
    private static final UUID GROUP = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @TempDir
    Path directory;

    @Test
    void savesAndLoadsStateWithTheDeviceKey() throws Exception {
        MlsIdentity alice = identity("alice");
        MlsIdentity bob = identity("bob");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of(MlsKeyPackage.create(bob)));
        SecretKey key = key((byte) 7);
        Path file = directory.resolve("nested/mls-state.bin");

        MlsStateStore.save(file, alice.userId(), key, alice, List.of(creation.state()));
        var loaded = MlsStateStore.load(file, alice.userId(), key).orElseThrow();

        assertThat(loaded.identity().userId()).isEqualTo(alice.userId());
        assertThat(loaded.groups()).hasSize(1);
        assertThat(loaded.groups().getFirst().groupId()).isEqualTo(GROUP);
        assertThat(loaded.groups().getFirst().epoch()).isEqualTo(1);
        assertThat(loaded.groups().getFirst().members()).containsKeys(alice.userId(), bob.userId());
    }

    @Test
    void rejectsTheWrongDeviceKeyAndMissingFilesAreEmpty() throws Exception {
        MlsIdentity alice = identity("alice");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of());
        Path file = directory.resolve("mls-state.bin");

        assertThat(MlsStateStore.load(file, alice.userId(), key((byte) 1))).isEmpty();
        MlsStateStore.save(file, alice.userId(), key((byte) 1), alice, List.of(creation.state()));

        assertThatThrownBy(() -> MlsStateStore.load(file, alice.userId(), key((byte) 2)))
                .isInstanceOf(IOException.class);
        assertThat(Files.size(file)).isPositive();
    }

    @Test
    void recoveryPackageRestoresTheSnapshotOnlyWithTheRightCode() throws Exception {
        MlsIdentity alice = identity("alice");
        var creation = MlsGroupCrypto.createGroup(GROUP, alice, List.of());
        String code = MlsStateStore.newRecoveryCode();

        byte[] packageBytes = MlsStateStore.encryptRecovery(code, alice, List.of(creation.state()));
        var restored = MlsStateStore.restoreRecovery(code, packageBytes);

        assertThat(restored.identity().userId()).isEqualTo(alice.userId());
        assertThat(restored.groups()).hasSize(1);
        assertThat(restored.groups().getFirst().epoch()).isEqualTo(1);
        assertThatThrownBy(() -> MlsStateStore.restoreRecovery(MlsStateStore.newRecoveryCode(), packageBytes))
                .isInstanceOf(MlsException.class);
    }

    private static MlsIdentity identity(String name) throws MlsException {
        return MlsIdentity.generate(UUID.nameUUIDFromBytes(name.getBytes()), "desktop-" + name);
    }

    private static SecretKey key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "AES");
    }
}
