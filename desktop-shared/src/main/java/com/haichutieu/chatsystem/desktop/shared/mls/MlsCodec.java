package com.haichutieu.chatsystem.desktop.shared.mls;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** Package-private binary and cryptographic helpers; never logs key material. */
final class MlsCodec {
    static final SecureRandom RANDOM = new SecureRandom();
    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BYTES = 16;
    static final int MAX_STRING_BYTES = 64;
    static final int MAX_BLOB_BYTES = 1 << 20;

    private MlsCodec() { }

    static byte[] random(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    static byte[] sha256(byte[] value) {
        SHA256Digest digest = new SHA256Digest();
        digest.update(value, 0, value.length);
        byte[] output = new byte[digest.getDigestSize()];
        digest.doFinal(output, 0);
        return output;
    }

    static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(new HKDFParameters(input, salt, info));
        byte[] output = new byte[length];
        generator.generateBytes(output, 0, length);
        return output;
    }

    static byte[] gcm(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, byte[] input)
            throws MlsException {
        if (key == null || key.length != KEY_BYTES || nonce == null || nonce.length != NONCE_BYTES) {
            throw new MlsException("Invalid AEAD parameters.");
        }
        try {
            GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
            cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
            byte[] output = new byte[cipher.getOutputSize(input.length)];
            int written = cipher.processBytes(input, 0, input.length, output, 0);
            written += cipher.doFinal(output, written);
            return written == output.length ? output : java.util.Arrays.copyOf(output, written);
        } catch (InvalidCipherTextException | RuntimeException exception) {
            throw new MlsException(encrypt
                    ? "Could not encrypt the payload."
                    : "Message authentication failed.", exception);
        }
    }

    static byte[] sign(Ed25519PrivateKeyParameters key, byte[] payload) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        signer.update(payload, 0, payload.length);
        return signer.generateSignature();
    }

    static boolean verify(byte[] publicKey, byte[] payload, byte[] signature) {
        if (publicKey == null || publicKey.length != 32 || signature == null || signature.length != 64) return false;
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        verifier.update(payload, 0, payload.length);
        return verifier.verifySignature(signature);
    }

    static boolean isZero(byte[] value) {
        int accumulator = 0;
        for (byte element : value) accumulator |= element;
        return accumulator == 0;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.writeBytes(part);
        return output.toByteArray();
    }

    static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static byte[] longBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    static byte[] label(String domain, UUID groupId, long epoch, UUID userId) {
        return concat(domain.getBytes(StandardCharsets.US_ASCII), uuidBytes(groupId), longBytes(epoch), uuidBytes(userId));
    }

    static byte[] label(String domain, UUID groupId, long epoch, UUID userId, UUID messageId) {
        return concat(label(domain, groupId, epoch, userId), uuidBytes(messageId));
    }

    static void writeBytes(DataOutputStream output, byte[] value, int maximum) throws IOException {
        if (value == null || value.length > maximum) throw new IOException("Field is too large.");
        output.writeInt(value.length);
        output.write(value);
    }

    static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) throw new IOException("Invalid field length.");
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    static void writeInstant(DataOutputStream output, Instant instant) throws IOException {
        output.writeLong(instant.getEpochSecond());
        output.writeInt(instant.getNano());
    }

    static Instant readInstant(DataInputStream input) throws IOException {
        long seconds = input.readLong();
        int nanos = input.readInt();
        if (nanos < 0 || nanos > 999_999_999) throw new IOException("Invalid timestamp.");
        return Instant.ofEpochSecond(seconds, nanos);
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeBytes(output, encoded, MAX_STRING_BYTES);
    }

    static String readString(DataInputStream input) throws IOException {
        String value = new String(readBytes(input, MAX_STRING_BYTES), StandardCharsets.UTF_8);
        if (value.isBlank() || value.length() > MAX_STRING_BYTES) throw new IOException("Invalid text field.");
        return value;
    }

    static void requireEmpty(DataInputStream input) throws IOException {
        if (input.available() != 0) throw new IOException("Trailing data.");
    }
}
