package com.nexus.desktop.shared.mls;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Long-lived device identity; secrets are only written inside encrypted stores or recovery packages. */
public final class MlsIdentity {
    private static final byte FORMAT = 1;
    private final UUID userId;
    private final String deviceId;
    private final byte[] signingSeed;
    private final byte[] initSecret;
    private final Ed25519PrivateKeyParameters signingKey;
    private final X25519PrivateKeyParameters initKey;

    private MlsIdentity(UUID userId, String deviceId, byte[] signingSeed, byte[] initSecret) throws MlsException {
        if (userId == null) throw new MlsException("A user ID is required.");
        String normalizedDevice = deviceId == null ? "" : deviceId.strip();
        if (normalizedDevice.isEmpty() || normalizedDevice.length() > 64
                || normalizedDevice.chars().anyMatch(Character::isISOControl)) {
            throw new MlsException("A valid device ID is required.");
        }
        if (signingSeed == null || signingSeed.length != 32 || initSecret == null || initSecret.length != 32) {
            throw new MlsException("Identity secrets must be 32 bytes each.");
        }
        this.userId = userId;
        this.deviceId = normalizedDevice;
        this.signingSeed = signingSeed.clone();
        this.initSecret = initSecret.clone();
        this.signingKey = new Ed25519PrivateKeyParameters(this.signingSeed, 0);
        this.initKey = new X25519PrivateKeyParameters(this.initSecret, 0);
    }

    public static MlsIdentity generate(UUID userId, String deviceId) throws MlsException {
        return new MlsIdentity(userId, deviceId, MlsCodec.random(32), MlsCodec.random(32));
    }

    public static MlsIdentity fromSecrets(UUID userId, String deviceId, byte[] signingSeed, byte[] initSecret)
            throws MlsException {
        return new MlsIdentity(userId, deviceId, signingSeed, initSecret);
    }

    public UUID userId() { return userId; }

    public String deviceId() { return deviceId; }

    public Ed25519PublicKeyParameters signingPublicKey() { return signingKey.generatePublicKey(); }

    public X25519PublicKeyParameters initPublicKey() { return initKey.generatePublicKey(); }

    X25519PrivateKeyParameters initKey() { return initKey; }

    public byte[] sign(byte[] payload) {
        return MlsCodec.sign(signingKey, payload);
    }

    public boolean verify(byte[] payload, byte[] signature) {
        return MlsCodec.verify(signingPublicKey().getEncoded(), payload, signature);
    }

    public byte[] encodeSecrets() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(FORMAT);
                MlsCodec.writeUuid(output, userId);
                MlsCodec.writeString(output, deviceId);
                MlsCodec.writeBytes(output, signingSeed, 32);
                MlsCodec.writeBytes(output, initSecret, 32);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode MLS identity.", exception);
        }
    }

    public static MlsIdentity decodeSecrets(byte[] encoded) throws MlsException {
        if (encoded == null) throw new MlsException("Missing MLS identity.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readByte() != FORMAT) throw new MlsException("Unsupported MLS identity format.");
            UUID userId = MlsCodec.readUuid(input);
            String deviceId = MlsCodec.readString(input);
            byte[] signingSeed = MlsCodec.readBytes(input, 32);
            byte[] initSecret = MlsCodec.readBytes(input, 32);
            MlsCodec.requireEmpty(input);
            if (signingSeed.length != 32 || initSecret.length != 32) {
                throw new MlsException("Invalid MLS identity secrets.");
            }
            return new MlsIdentity(userId, deviceId, signingSeed, initSecret);
        } catch (IOException exception) {
            throw new MlsException("Invalid MLS identity encoding.", exception);
        }
    }

    @Override
    public String toString() {
        return "MlsIdentity[" + userId + ", " + deviceId + "]";
    }
}
