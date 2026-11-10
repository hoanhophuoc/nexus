package com.haichutieu.chatsystem.desktop.shared.mls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A signed hard-reset commit: it installs a fresh epoch secret for every retained member. */
public record MlsCommit(UUID groupId, long epoch, byte[] previousEpochHash, UUID committerId,
                        List<UUID> added, List<UUID> removed, List<Member> members,
                        Map<UUID, Welcome> welcomes, byte[] signature) {
    private static final byte FORMAT = 1;
    private static final byte[] DOMAIN = "chat-mls-commit-v1".getBytes(StandardCharsets.US_ASCII);

    public record Member(UUID userId, MlsKeyPackage keyPackage) {
        public Member {
            if (userId == null || keyPackage == null) throw new IllegalArgumentException("Member data is required.");
        }
    }

    public record Welcome(byte[] ephemeralKey, byte[] nonce, byte[] ciphertext) {
        public Welcome {
            if (ephemeralKey == null || ephemeralKey.length != 32 || nonce == null || nonce.length != 12
                    || ciphertext == null || ciphertext.length != 48) {
                throw new IllegalArgumentException("Malformed welcome.");
            }
            ephemeralKey = ephemeralKey.clone();
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }
    }

    public MlsCommit {
        if (groupId == null || epoch <= 0 || committerId == null) {
            throw new IllegalArgumentException("Commit group, epoch and committer are required.");
        }
        if (previousEpochHash == null || previousEpochHash.length != 32) {
            throw new IllegalArgumentException("Previous epoch hash must be 32 bytes.");
        }
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
        if (new HashSet<>(added).size() != added.size() || new HashSet<>(removed).size() != removed.size()
                || added.stream().anyMatch(removed::contains) || removed.contains(committerId)) {
            throw new IllegalArgumentException("Commit member changes are inconsistent.");
        }
        members = members == null ? List.of() : List.copyOf(members);
        if (members.isEmpty() || members.size() > 512) {
            throw new IllegalArgumentException("Commit roster size is invalid.");
        }
        Map<UUID, MlsKeyPackage> roster = new HashMap<>();
        for (Member member : members) {
            if (member == null || !member.userId().equals(member.keyPackage().userId())
                    || roster.put(member.userId(), member.keyPackage()) != null) {
                throw new IllegalArgumentException("Commit roster contains duplicate or mismatched members.");
            }
        }
        if (!roster.keySet().containsAll(added) || roster.keySet().stream().anyMatch(removed::contains)) {
            throw new IllegalArgumentException("Commit changes do not match the roster.");
        }
        welcomes = welcomes == null ? Map.of() : Map.copyOf(welcomes);
        for (Map.Entry<UUID, Welcome> entry : welcomes.entrySet()) {
            if (!roster.containsKey(entry.getKey()) || entry.getKey().equals(committerId) || entry.getValue() == null) {
                throw new IllegalArgumentException("Commit welcomes do not match the roster.");
            }
        }
        if (signature != null && signature.length != 64) {
            throw new IllegalArgumentException("Commit signature must be 64 bytes.");
        }
        members = members.stream().sorted(Comparator.comparing(Member::userId)).toList();
        previousEpochHash = previousEpochHash.clone();
        signature = signature == null ? null : signature.clone();
    }

    public Map<UUID, MlsKeyPackage> memberMap() {
        Map<UUID, MlsKeyPackage> roster = new java.util.TreeMap<>();
        for (Member member : members) roster.put(member.userId(), member.keyPackage());
        return java.util.Collections.unmodifiableMap(roster);
    }

    public void verify() throws MlsException {
        if (signature == null) throw new MlsException("Commit is not signed.");
        for (Member member : members) member.keyPackage().verifySignature();
        MlsKeyPackage committer = memberMap().get(committerId);
        if (committer == null) throw new MlsException("Commit signer is not in the roster.");
        if (!MlsCodec.verify(committer.identityKey(), signedPayload(), signature)) {
            throw new MlsException("Commit signature is invalid.");
        }
    }

    public MlsCommit sign(MlsIdentity identity) throws MlsException {
        if (identity == null || !identity.userId().equals(committerId)) {
            throw new MlsException("Only the listed committer can sign this commit.");
        }
        return new MlsCommit(groupId, epoch, previousEpochHash, committerId, added, removed, members,
                welcomes, identity.sign(signedPayload()));
    }

    public byte[] encode() {
        if (signature == null) throw new IllegalStateException("Commit must be signed before encoding.");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(signedPayload());
                MlsCodec.writeBytes(output, signature, 64);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode commit.", exception);
        }
    }

    public static MlsCommit decode(byte[] encoded) throws MlsException {
        if (encoded == null) throw new MlsException("Missing commit.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readByte() != FORMAT) throw new MlsException("Unsupported commit format.");
            if (!Arrays.equals(MlsCodec.readBytes(input, 64), DOMAIN)) throw new MlsException("Invalid commit domain.");
            UUID groupId = MlsCodec.readUuid(input);
            long epoch = input.readLong();
            byte[] previous = MlsCodec.readBytes(input, 32);
            UUID committerId = MlsCodec.readUuid(input);
            List<UUID> added = readIds(input);
            List<UUID> removed = readIds(input);
            int memberCount = input.readInt();
            if (memberCount < 1 || memberCount > 512) throw new MlsException("Invalid commit roster size.");
            List<Member> members = new ArrayList<>(memberCount);
            for (int index = 0; index < memberCount; index++) {
                UUID userId = MlsCodec.readUuid(input);
                members.add(new Member(userId, MlsKeyPackage.decode(MlsCodec.readBytes(input, 4096))));
            }
            int welcomeCount = input.readInt();
            if (welcomeCount < 0 || welcomeCount > 512) throw new MlsException("Invalid welcome count.");
            Map<UUID, Welcome> welcomes = new LinkedHashMap<>();
            for (int index = 0; index < welcomeCount; index++) {
                UUID userId = MlsCodec.readUuid(input);
                Welcome welcome = new Welcome(MlsCodec.readBytes(input, 32), MlsCodec.readBytes(input, 12),
                        MlsCodec.readBytes(input, 128));
                if (welcomes.put(userId, welcome) != null) throw new MlsException("Duplicate welcome.");
            }
            byte[] signature = MlsCodec.readBytes(input, 64);
            MlsCodec.requireEmpty(input);
            return new MlsCommit(groupId, epoch, previous, committerId, added, removed, members, welcomes, signature);
        } catch (IOException | IllegalArgumentException exception) {
            throw new MlsException("Invalid commit encoding.", exception);
        }
    }

    private byte[] signedPayload() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(FORMAT);
                MlsCodec.writeBytes(output, DOMAIN, 64);
                MlsCodec.writeUuid(output, groupId);
                output.writeLong(epoch);
                MlsCodec.writeBytes(output, previousEpochHash, 32);
                MlsCodec.writeUuid(output, committerId);
                writeIds(output, added);
                writeIds(output, removed);
                output.writeInt(members.size());
                for (Member member : members) {
                    MlsCodec.writeUuid(output, member.userId());
                    MlsCodec.writeBytes(output, member.keyPackage().encode(), 4096);
                }
                output.writeInt(welcomes.size());
                for (UUID userId : new java.util.TreeSet<>(welcomes.keySet())) {
                    Welcome welcome = welcomes.get(userId);
                    MlsCodec.writeUuid(output, userId);
                    MlsCodec.writeBytes(output, welcome.ephemeralKey(), 32);
                    MlsCodec.writeBytes(output, welcome.nonce(), 12);
                    MlsCodec.writeBytes(output, welcome.ciphertext(), 128);
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode commit payload.", exception);
        }
    }

    private static List<UUID> readIds(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 512) throw new IOException("Invalid member change count.");
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(MlsCodec.readUuid(input));
        return List.copyOf(ids);
    }

    private static void writeIds(DataOutputStream output, List<UUID> ids) throws IOException {
        output.writeInt(ids.size());
        for (UUID id : ids) MlsCodec.writeUuid(output, id);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MlsCommit commit)) return false;
        return groupId.equals(commit.groupId) && epoch == commit.epoch
                && Arrays.equals(previousEpochHash, commit.previousEpochHash)
                && committerId.equals(commit.committerId) && added.equals(commit.added)
                && removed.equals(commit.removed) && members.equals(commit.members)
                && welcomes.equals(commit.welcomes) && Arrays.equals(signature, commit.signature);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(groupId, epoch, committerId, added, removed, members, welcomes);
    }

    @Override
    public String toString() {
        return "MlsCommit[" + groupId + ", epoch=" + epoch + ", members=" + members.size() + "]";
    }
}
