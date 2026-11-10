package com.haichutieu.chatsystem.desktop.shared.mls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/** Wire envelope for one encrypted and sender-signed group message. */
public record MlsApplicationMessage(UUID groupId, long epoch, UUID senderId, UUID messageId,
                                    byte[] nonce, byte[] ciphertext, byte[] signature) {
    private static final byte FORMAT = 1;

    public MlsApplicationMessage {
        if (groupId == null || epoch < 0 || senderId == null || messageId == null) {
            throw new IllegalArgumentException("Message header is incomplete.");
        }
        if (nonce == null || nonce.length != 12 || ciphertext == null || ciphertext.length < 16
                || ciphertext.length > 70_000) {
            throw new IllegalArgumentException("Message ciphertext is invalid.");
        }
        if (signature == null || signature.length != 64) {
            throw new IllegalArgumentException("Message signature must be 64 bytes.");
        }
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
        signature = signature.clone();
    }

    /** Fields covered by the sender signature; excludes the signature itself. */
    byte[] signedPayload() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(FORMAT);
                MlsCodec.writeUuid(output, groupId);
                output.writeLong(epoch);
                MlsCodec.writeUuid(output, senderId);
                MlsCodec.writeUuid(output, messageId);
                MlsCodec.writeBytes(output, nonce, 12);
                MlsCodec.writeBytes(output, ciphertext, 70_000);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode application message payload.", exception);
        }
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
            throw new IllegalStateException("Could not encode application message.", exception);
        }
    }

    public static MlsApplicationMessage decode(byte[] encoded) throws MlsException {
        if (encoded == null) throw new MlsException("Missing application message.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readByte() != FORMAT) throw new MlsException("Unsupported message format.");
            UUID groupId = MlsCodec.readUuid(input);
            long epoch = input.readLong();
            UUID senderId = MlsCodec.readUuid(input);
            UUID messageId = MlsCodec.readUuid(input);
            byte[] nonce = MlsCodec.readBytes(input, 12);
            byte[] ciphertext = MlsCodec.readBytes(input, 70_000);
            byte[] signature = MlsCodec.readBytes(input, 64);
            MlsCodec.requireEmpty(input);
            return new MlsApplicationMessage(groupId, epoch, senderId, messageId, nonce, ciphertext, signature);
        } catch (IOException | IllegalArgumentException exception) {
            throw new MlsException("Invalid application message encoding.", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MlsApplicationMessage message && groupId.equals(message.groupId)
                && epoch == message.epoch && senderId.equals(message.senderId)
                && messageId.equals(message.messageId) && Arrays.equals(nonce, message.nonce)
                && Arrays.equals(ciphertext, message.ciphertext)
                && Arrays.equals(signature, message.signature);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(groupId, epoch, senderId, messageId);
    }

    @Override
    public String toString() {
        return "MlsApplicationMessage[" + groupId + ", epoch=" + epoch + ", sender=" + senderId + "]";
    }
}
