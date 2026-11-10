package com.haichutieu.chatsystem.desktop.shared.mls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Epoch secret, roster and replay cache for one group; encode only inside encrypted storage. */
public final class MlsGroupState {
    private static final byte FORMAT = 1;
    private static final int MAX_SEEN = 4096;
    private final UUID groupId;
    private final long epoch;
    private final byte[] epochSecret;
    private final byte[] epochHash;
    private final Map<UUID, MlsKeyPackage> members;
    private final LinkedHashSet<UUID> seenMessages = new LinkedHashSet<>();

    MlsGroupState(UUID groupId, long epoch, byte[] epochSecret, byte[] epochHash,
                  Map<UUID, MlsKeyPackage> members) {
        if (groupId == null || epoch < 0 || epochSecret == null || epochSecret.length != 32
                || epochHash == null || epochHash.length != 32 || members == null || members.isEmpty()
                || members.size() > 512) {
            throw new IllegalArgumentException("Invalid group state.");
        }
        TreeMap<UUID, MlsKeyPackage> roster = new TreeMap<>();
        for (Map.Entry<UUID, MlsKeyPackage> entry : members.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !entry.getKey().equals(entry.getValue().userId())) {
                throw new IllegalArgumentException("Group roster is inconsistent.");
            }
            roster.put(entry.getKey(), entry.getValue());
        }
        this.groupId = groupId;
        this.epoch = epoch;
        this.epochSecret = epochSecret.clone();
        this.epochHash = epochHash.clone();
        this.members = Collections.unmodifiableMap(roster);
    }

    public UUID groupId() { return groupId; }

    public long epoch() { return epoch; }

    public Map<UUID, MlsKeyPackage> members() { return members; }

    public boolean isMember(UUID userId) { return members.containsKey(userId); }

    byte[] secret() { return epochSecret.clone(); }

    byte[] hash() { return epochHash.clone(); }

    synchronized boolean hasSeen(UUID messageId) {
        return seenMessages.contains(messageId);
    }

    synchronized boolean markSeen(UUID messageId) {
        if (!seenMessages.add(messageId)) return false;
        while (seenMessages.size() > MAX_SEEN) seenMessages.remove(seenMessages.iterator().next());
        return true;
    }

    public synchronized byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(FORMAT);
                MlsCodec.writeUuid(output, groupId);
                output.writeLong(epoch);
                MlsCodec.writeBytes(output, epochSecret, 32);
                MlsCodec.writeBytes(output, epochHash, 32);
                output.writeInt(members.size());
                for (Map.Entry<UUID, MlsKeyPackage> entry : members.entrySet()) {
                    MlsCodec.writeUuid(output, entry.getKey());
                    MlsCodec.writeBytes(output, entry.getValue().encode(), 4096);
                }
                output.writeInt(seenMessages.size());
                for (UUID messageId : seenMessages) MlsCodec.writeUuid(output, messageId);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode group state.", exception);
        }
    }

    public static MlsGroupState decode(byte[] encoded) throws MlsException {
        if (encoded == null) throw new MlsException("Missing group state.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readByte() != FORMAT) throw new MlsException("Unsupported group state format.");
            UUID groupId = MlsCodec.readUuid(input);
            long epoch = input.readLong();
            byte[] secret = MlsCodec.readBytes(input, 32);
            byte[] hash = MlsCodec.readBytes(input, 32);
            int memberCount = input.readInt();
            if (memberCount < 1 || memberCount > 512) throw new MlsException("Invalid group roster size.");
            Map<UUID, MlsKeyPackage> members = new TreeMap<>();
            for (int index = 0; index < memberCount; index++) {
                UUID userId = MlsCodec.readUuid(input);
                MlsKeyPackage keyPackage = MlsKeyPackage.decode(MlsCodec.readBytes(input, 4096));
                if (members.put(userId, keyPackage) != null) throw new MlsException("Duplicate group member.");
            }
            int seenCount = input.readInt();
            if (seenCount < 0 || seenCount > MAX_SEEN) throw new MlsException("Invalid replay cache.");
            List<UUID> seen = new ArrayList<>(seenCount);
            for (int index = 0; index < seenCount; index++) seen.add(MlsCodec.readUuid(input));
            MlsCodec.requireEmpty(input);
            MlsGroupState state = new MlsGroupState(groupId, epoch, secret, hash, members);
            seen.forEach(state.seenMessages::add);
            return state;
        } catch (IOException | IllegalArgumentException exception) {
            throw new MlsException("Invalid group state encoding.", exception);
        }
    }

    @Override
    public String toString() {
        return "MlsGroupState[" + groupId + ", epoch=" + epoch + ", members=" + members.size() + "]";
    }
}
