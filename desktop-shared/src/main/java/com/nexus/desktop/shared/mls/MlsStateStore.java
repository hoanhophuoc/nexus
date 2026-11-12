package com.nexus.desktop.shared.mls;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Encrypted-at-rest group state plus a user-held recovery code; never persists plaintext keys. */
public final class MlsStateStore {
    private static final int MAGIC = 0x4D4C5331;
    private static final byte[] RECOVERY_MAGIC = "MLSR".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final byte[] STATE_AAD = "chat-mls-state-file-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RECOVERY_AAD = "chat-mls-recovery-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATE_SALT = "chat-mls-state-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 310_000;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_RECOVERY_BYTES = 1_048_576;
    private static final int MAX_GROUPS = 1000;

    private MlsStateStore() { }

    public record Snapshot(MlsIdentity identity, List<MlsGroupState> groups) {
        public Snapshot {
            if (identity == null || groups == null || groups.size() > MAX_GROUPS) {
                throw new IllegalArgumentException("Invalid MLS state snapshot.");
            }
            groups = List.copyOf(groups);
        }
    }

    public static void save(Path file, UUID ownerId, char[] password, MlsIdentity identity,
                            Collection<MlsGroupState> groups) throws IOException {
        if (password == null) throw new IllegalArgumentException("A password is required.");
        requireArguments(file, ownerId, password, identity);
        byte[] salt = MlsCodec.concat(STATE_SALT, MlsCodec.uuidBytes(ownerId));
        try {
            saveEncrypted(file, ownerId, deriveKey(password, salt), identity, groups);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not derive the MLS state key.", exception);
        }
    }

    /** Uses an already-derived device key (for example the account cache key) instead of a password. */
    public static void save(Path file, UUID ownerId, SecretKey key, MlsIdentity identity,
                            Collection<MlsGroupState> groups) throws IOException {
        if (identity == null) throw new IllegalArgumentException("An MLS identity is required.");
        requireArguments(file, ownerId, null, identity);
        requireKey(key);
        saveEncrypted(file, ownerId, key.getEncoded(), identity, groups);
    }

    public static Optional<Snapshot> load(Path file, UUID ownerId, char[] password) throws IOException {
        if (password == null) throw new IllegalArgumentException("A password is required.");
        requireArguments(file, ownerId, password, null);
        byte[] salt = MlsCodec.concat(STATE_SALT, MlsCodec.uuidBytes(ownerId));
        try {
            return loadEncrypted(file, ownerId, deriveKey(password, salt));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not derive the MLS state key.", exception);
        }
    }

    public static Optional<Snapshot> load(Path file, UUID ownerId, SecretKey key) throws IOException {
        requireArguments(file, ownerId, null, null);
        requireKey(key);
        return loadEncrypted(file, ownerId, key.getEncoded());
    }

    private static void saveEncrypted(Path file, UUID ownerId, byte[] key, MlsIdentity identity,
                                      Collection<MlsGroupState> groups) throws IOException {
        byte[] nonce = MlsCodec.randomBytes(12);
        byte[] aad = MlsCodec.concat(STATE_AAD, MlsCodec.uuidBytes(ownerId), new byte[]{VERSION});
        try {
            byte[] plaintext = encodeSnapshot(identity, groups);
            byte[] ciphertext = MlsCodec.gcm(true, key, nonce, aad, plaintext);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                MlsCodec.writeBytes(output, nonce, 12);
                MlsCodec.writeBytes(output, ciphertext, MAX_FILE_BYTES);
            }
            writeAtomically(file, bytes.toByteArray());
        } catch (MlsException exception) {
            throw new IOException("Could not encrypt MLS state.", exception);
        }
    }

    private static Optional<Snapshot> loadEncrypted(Path file, UUID ownerId, byte[] key) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        byte[] envelope = Files.readAllBytes(file);
        if (envelope.length < 4 + 1 + 12 + 16 || envelope.length > MAX_FILE_BYTES) {
            throw new IOException("Encrypted MLS state has an invalid size.");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (input.readInt() != MAGIC || input.readByte() != VERSION) {
                throw new IOException("Unsupported MLS state format.");
            }
            byte[] nonce = MlsCodec.readBytes(input, 12);
            byte[] ciphertext = MlsCodec.readBytes(input, MAX_FILE_BYTES);
            MlsCodec.requireEmpty(input);
            byte[] aad = MlsCodec.concat(STATE_AAD, MlsCodec.uuidBytes(ownerId), new byte[]{VERSION});
            byte[] plaintext = MlsCodec.gcm(false, key, nonce, aad, ciphertext);
            return Optional.of(decodeSnapshot(plaintext));
        } catch (MlsException exception) {
            throw new IOException("Encrypted MLS state authentication failed.", exception);
        }
    }

    private static void requireKey(SecretKey key) {
        if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm()) || key.getEncoded() == null
                || key.getEncoded().length != 32) {
            throw new IllegalArgumentException("A 256-bit AES state key is required.");
        }
    }

    public static String newRecoveryCode() {
        String hex = HexFormat.of().formatHex(MlsCodec.randomBytes(32)).toUpperCase(Locale.ROOT);
        StringBuilder code = new StringBuilder(hex.length() + 7);
        for (int index = 0; index < hex.length(); index += 8) {
            if (index > 0) code.append('-');
            code.append(hex, index, Math.min(index + 8, hex.length()));
        }
        return code.toString();
    }

    public static byte[] encryptRecovery(String code, MlsIdentity identity, Collection<MlsGroupState> groups)
            throws MlsException {
        byte[] normalized = normalizeCode(code);
        byte[] plaintext = encodeSnapshot(identity, groups);
        byte[] salt = MlsCodec.randomBytes(16);
        byte[] nonce = MlsCodec.randomBytes(12);
        byte[] aad = MlsCodec.concat(RECOVERY_AAD, new byte[]{VERSION});
        try {
            byte[] ciphertext = MlsCodec.gcm(true,
                    deriveKey(new String(normalized, StandardCharsets.US_ASCII).toCharArray(), salt),
                    nonce, aad, plaintext);
            if (ciphertext.length > MAX_RECOVERY_BYTES) {
                throw new MlsException("Recovery package exceeds 1 MiB.");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(RECOVERY_MAGIC);
                output.writeByte(VERSION);
                MlsCodec.writeBytes(output, salt, 16);
                MlsCodec.writeBytes(output, nonce, 12);
                MlsCodec.writeBytes(output, ciphertext, MAX_FILE_BYTES);
            }
            return bytes.toByteArray();
        } catch (GeneralSecurityException | IOException exception) {
            throw new MlsException("Could not create the recovery package.", exception);
        }
    }

    public static Snapshot restoreRecovery(String code, byte[] packageBytes) throws MlsException {
        byte[] normalized = normalizeCode(code);
        if (packageBytes == null || packageBytes.length < 4 + 1 + 16 + 12 + 16
                || packageBytes.length > MAX_RECOVERY_BYTES) {
            throw new MlsException("Recovery package has an invalid size.");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packageBytes))) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!java.util.Arrays.equals(magic, RECOVERY_MAGIC) || input.readByte() != VERSION) {
                throw new MlsException("Unsupported recovery package format.");
            }
            byte[] salt = MlsCodec.readBytes(input, 16);
            byte[] nonce = MlsCodec.readBytes(input, 12);
            byte[] ciphertext = MlsCodec.readBytes(input, MAX_FILE_BYTES);
            MlsCodec.requireEmpty(input);
            byte[] plaintext;
            try {
                plaintext = MlsCodec.gcm(false,
                        deriveKey(new String(normalized, StandardCharsets.US_ASCII).toCharArray(), salt),
                        nonce, MlsCodec.concat(RECOVERY_AAD, new byte[]{VERSION}), ciphertext);
            } catch (MlsException exception) {
                throw new MlsException("Recovery code is wrong or the package was tampered with.", exception);
            }
            return decodeSnapshot(plaintext);
        } catch (GeneralSecurityException | IOException exception) {
            throw new MlsException("Recovery package is malformed.", exception);
        }
    }

    private static byte[] encodeSnapshot(MlsIdentity identity, Collection<MlsGroupState> groups)
            throws MlsException {
        if (identity == null) throw new MlsException("An MLS identity is required.");
        List<MlsGroupState> list = groups == null ? List.of() : List.copyOf(groups);
        if (list.size() > MAX_GROUPS) throw new MlsException("Too many groups in MLS state.");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(VERSION);
                MlsCodec.writeBytes(output, identity.encodeSecrets(), 1024);
                output.writeInt(list.size());
                for (MlsGroupState group : list) {
                    if (group == null) throw new MlsException("Group state is missing.");
                    MlsCodec.writeBytes(output, group.encode(), MAX_FILE_BYTES);
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new MlsException("Could not encode MLS state.", exception);
        }
    }

    private static Snapshot decodeSnapshot(byte[] plaintext) throws MlsException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readByte() != VERSION) throw new MlsException("Unsupported MLS state version.");
            MlsIdentity identity = MlsIdentity.decodeSecrets(MlsCodec.readBytes(input, 1024));
            int count = input.readInt();
            if (count < 0 || count > MAX_GROUPS) throw new MlsException("Invalid group count.");
            List<MlsGroupState> groups = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                groups.add(MlsGroupState.decode(MlsCodec.readBytes(input, MAX_FILE_BYTES)));
            }
            MlsCodec.requireEmpty(input);
            return new Snapshot(identity, groups);
        } catch (IOException | IllegalArgumentException exception) {
            throw new MlsException("Invalid MLS state encoding.", exception);
        }
    }

    private static byte[] deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] normalizeCode(String code) throws MlsException {
        if (code == null) throw new MlsException("A recovery code is required.");
        String normalized = code.replace("-", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new MlsException("Recovery code must contain 64 hexadecimal characters.");
        }
        return normalized.getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireArguments(Path file, UUID ownerId, char[] password, MlsIdentity identity) {
        if (file == null || ownerId == null || (password != null && password.length == 0)) {
            throw new IllegalArgumentException("A file, account ID and key material are required.");
        }
        if (identity != null && identity.userId() == null) {
            throw new IllegalArgumentException("A valid MLS identity is required.");
        }
    }

    private static void writeAtomically(Path file, byte[] content) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = parent != null && Files.getFileStore(parent).supportsFileAttributeView("posix")
                ? Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
