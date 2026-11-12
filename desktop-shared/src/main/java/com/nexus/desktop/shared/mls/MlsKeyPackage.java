package com.nexus.desktop.shared.mls;

import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** A signed, expiring key package. Its signature covers the identity and welcome key. */
public record MlsKeyPackage(UUID userId, String deviceId, byte[] identityKey, byte[] initKey,
                            Instant createdAt, Instant expiresAt, byte[] signature) {
    private static final byte FORMAT = 1;
    private static final byte[] DOMAIN = "chat-mls-key-package-v1".getBytes(StandardCharsets.US_ASCII);

    public MlsKeyPackage {
        if (userId == null || deviceId == null || deviceId.isBlank() || deviceId.length() > 64) {
            throw new IllegalArgumentException("A user ID and device ID are required.");
        }
        if (identityKey == null || identityKey.length != 32 || initKey == null || initKey.length != 32) {
            throw new IllegalArgumentException("Key package keys must be 32 bytes.");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Key package timestamps are invalid.");
        }
        if (signature == null || signature.length != 64) {
            throw new IllegalArgumentException("Key package signature must be 64 bytes.");
        }
        identityKey = identityKey.clone();
        initKey = initKey.clone();
        signature = signature.clone();
    }

    public static MlsKeyPackage create(MlsIdentity identity) throws MlsException {
        return create(identity, Instant.now(), Duration.ofDays(30));
    }

    public static MlsKeyPackage create(MlsIdentity identity, Instant now, Duration lifetime) throws MlsException {
        if (identity == null || now == null || lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new MlsException("A key package lifetime is required.");
        }
        byte[] identityKey = identity.signingPublicKey().getEncoded();
        byte[] initKey = identity.initPublicKey().getEncoded();
        Instant expiresAt = now.plus(lifetime);
        byte[] payload = payload(identity.userId(), identity.deviceId(), identityKey, initKey, now, expiresAt);
        return new MlsKeyPackage(identity.userId(), identity.deviceId(), identityKey, initKey,
                now, expiresAt, identity.sign(payload));
    }

    public void verify(Instant now) throws MlsException {
        if (now == null) throw new MlsException("A verification time is required.");
        if (!now.isBefore(expiresAt)) throw new MlsException("Key package has expired.");
        verifySignature();
    }

    /** Verifies authenticity without enforcing expiry, for long-lived group rosters. */
    public void verifySignature() throws MlsException {
        if (!MlsCodec.verify(identityKey, signedPayload(), signature)) {
            throw new MlsException("Key package signature is invalid.");
        }
    }

    public X25519PublicKeyParameters initPublicKey() {
        return new X25519PublicKeyParameters(initKey, 0);
    }

    public boolean sameAs(MlsKeyPackage other) {
        return other != null && userId.equals(other.userId) && deviceId.equals(other.deviceId)
                && Arrays.equals(identityKey, other.identityKey) && Arrays.equals(initKey, other.initKey)
                && createdAt.equals(other.createdAt) && expiresAt.equals(other.expiresAt);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(signedPayload());
                MlsCodec.writeBytes(output, signature, 64);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode key package.", exception);
        }
    }

    public static MlsKeyPackage decode(byte[] encoded) throws MlsException {
        if (encoded == null) throw new MlsException("Missing key package.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readByte() != FORMAT) throw new MlsException("Unsupported key package format.");
            byte[] domain = MlsCodec.readBytes(input, 64);
            if (!Arrays.equals(domain, DOMAIN)) throw new MlsException("Invalid key package domain.");
            UUID userId = MlsCodec.readUuid(input);
            String deviceId = MlsCodec.readString(input);
            byte[] identityKey = MlsCodec.readBytes(input, 32);
            byte[] initKey = MlsCodec.readBytes(input, 32);
            Instant createdAt = MlsCodec.readInstant(input);
            Instant expiresAt = MlsCodec.readInstant(input);
            byte[] signature = MlsCodec.readBytes(input, 64);
            MlsCodec.requireEmpty(input);
            return new MlsKeyPackage(userId, deviceId, identityKey, initKey, createdAt, expiresAt, signature);
        } catch (IOException | IllegalArgumentException exception) {
            throw new MlsException("Invalid key package encoding.", exception);
        }
    }

    private byte[] signedPayload() {
        return payload(userId, deviceId, identityKey, initKey, createdAt, expiresAt);
    }

    private static byte[] payload(UUID userId, String deviceId, byte[] identityKey, byte[] initKey,
                                  Instant createdAt, Instant expiresAt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(FORMAT);
                MlsCodec.writeBytes(output, DOMAIN, 64);
                MlsCodec.writeUuid(output, userId);
                MlsCodec.writeString(output, deviceId);
                MlsCodec.writeBytes(output, identityKey, 32);
                MlsCodec.writeBytes(output, initKey, 32);
                MlsCodec.writeInstant(output, createdAt);
                MlsCodec.writeInstant(output, expiresAt);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode key package payload.", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MlsKeyPackage keyPackage && sameAs(keyPackage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, deviceId, Arrays.hashCode(identityKey), Arrays.hashCode(initKey), createdAt, expiresAt);
    }
}
