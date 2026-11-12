package com.nexus.desktop.shared.mls;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Epoch-ratcheted end-to-end group encryption built on Bouncy Castle 1.86 primitives.
 *
 * <p>Bouncy Castle's {@code bcmls} artifact exposes only the low-level RFC 9420 {@code Group}
 * state machine (no key-package factory and no serializable client state), so this layer does
 * NOT implement RFC 9420 wire compatibility. It provides signed key packages, signed
 * add/remove commits, per-recipient welcomes, per-epoch secrets and replay rejection. Never
 * describe it as RFC 9420 MLS in product copy.</p>
 */
public final class MlsGroupCrypto {
    private static final byte[] COMMIT_DOMAIN = "chat-mls-commit-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EPOCH_DOMAIN = "chat-mls-epoch-v1".getBytes(StandardCharsets.US_ASCII);
    private static final String APP_KEY = "chat-mls-app-key-v1";
    private static final String APP_AAD = "chat-mls-app-aad-v1";
    private static final String WELCOME_KEY = "chat-mls-welcome-key-v1";
    private static final String WELCOME_AAD = "chat-mls-welcome-aad-v1";
    private static final int MAX_PLAINTEXT = 60_000;
    private static final int MAX_MEMBERS = 512;

    private MlsGroupCrypto() { }

    public record Creation(MlsGroupState state, MlsCommit commit) { }

    public record CommitResult(MlsGroupState state, MlsCommit commit) { }

    public static Creation createGroup(UUID groupId, MlsIdentity creator, Collection<MlsKeyPackage> invitees)
            throws MlsException {
        if (groupId == null || creator == null) throw new MlsException("A group and creator are required.");
        Instant now = Instant.now();
        TreeMap<UUID, MlsKeyPackage> roster = new TreeMap<>();
        roster.put(creator.userId(), MlsKeyPackage.create(creator));
        if (invitees != null) {
            for (MlsKeyPackage invitee : invitees) {
                if (invitee == null) throw new MlsException("Invitee key package is missing.");
                invitee.verify(now);
                if (roster.putIfAbsent(invitee.userId(), invitee) != null) {
                    throw new MlsException("Duplicate group member: " + invitee.userId());
                }
            }
        }
        if (roster.size() > MAX_MEMBERS) throw new MlsException("The group is too large.");
        byte[] genesis = epochHash(groupId, 0, new byte[32], roster);
        byte[] secret = MlsCodec.randomBytes(32);
        long epoch = 1;
        byte[] hash = epochHash(groupId, epoch, genesis, roster);
        MlsCommit unsigned = new MlsCommit(groupId, epoch, genesis, creator.userId(), List.of(), List.of(),
                memberList(roster), welcomes(roster, creator.userId(), groupId, epoch, secret), null);
        return new Creation(new MlsGroupState(groupId, epoch, secret, hash, roster), unsigned.sign(creator));
    }

    /**
     * Builds a new epoch locally. Adopt {@code result.state()} only after the server accepted
     * {@code result.commit()}; a rejected commit must not advance the local epoch.
     */
    public static CommitResult commit(MlsGroupState current, MlsIdentity committer,
                                      Collection<MlsKeyPackage> additions, Collection<UUID> removals)
            throws MlsException {
        if (current == null || committer == null || !current.isMember(committer.userId())) {
            throw new MlsException("Only a current member can commit.");
        }
        Instant now = Instant.now();
        TreeMap<UUID, MlsKeyPackage> roster = new TreeMap<>(current.members());
        List<UUID> added = new ArrayList<>();
        if (additions != null) {
            for (MlsKeyPackage addition : additions) {
                if (addition == null) throw new MlsException("Added key package is missing.");
                addition.verify(now);
                if (roster.putIfAbsent(addition.userId(), addition) != null) {
                    throw new MlsException("Member already exists: " + addition.userId());
                }
                added.add(addition.userId());
            }
        }
        List<UUID> removed = new ArrayList<>();
        if (removals != null) {
            for (UUID removal : removals) {
                if (removal == null || removal.equals(committer.userId())) {
                    throw new MlsException("A committer cannot remove itself.");
                }
                if (roster.remove(removal) == null) throw new MlsException("Member is not in the group: " + removal);
                removed.add(removal);
            }
        }
        if (added.isEmpty() && removed.isEmpty()) throw new MlsException("A commit must add or remove a member.");
        if (roster.size() > MAX_MEMBERS) throw new MlsException("The group is too large.");
        Collections.sort(added);
        Collections.sort(removed);
        long epoch = current.epoch() + 1;
        byte[] secret = MlsCodec.randomBytes(32);
        byte[] hash = epochHash(current.groupId(), epoch, current.hash(), roster);
        MlsCommit unsigned = new MlsCommit(current.groupId(), epoch, current.hash(), committer.userId(),
                added, removed, memberList(roster),
                welcomes(roster, committer.userId(), current.groupId(), epoch, secret), null);
        return new CommitResult(new MlsGroupState(current.groupId(), epoch, secret, hash, roster),
                unsigned.sign(committer));
    }

    public static MlsGroupState applyCommit(MlsGroupState current, UUID groupId, MlsIdentity identity,
                                            byte[] encodedCommit) throws MlsException {
        return applyCommit(current, groupId, current == null ? -1 : current.epoch(), identity, encodedCommit);
    }

    /**
     * Applies a commit and, when {@code expectedEpoch} is non-negative, requires that exact
     * epoch. Pass the server's current epoch when a newly invited member joins so an old
     * welcome cannot be replayed.
     */
    public static MlsGroupState applyCommit(MlsGroupState current, UUID groupId, long expectedEpoch,
                                            MlsIdentity identity, byte[] encodedCommit) throws MlsException {
        if (groupId == null || identity == null) throw new MlsException("A group and identity are required.");
        MlsCommit commit = MlsCommit.decode(encodedCommit);
        if (!commit.groupId().equals(groupId)) throw new MlsException("Commit belongs to another group.");
        commit.verify();
        Map<UUID, MlsKeyPackage> roster = commit.memberMap();
        if (!roster.containsKey(identity.userId())) throw new MlsException("This account is not in the commit roster.");
        if (current == null) {
            if (expectedEpoch >= 0 && commit.epoch() != expectedEpoch) {
                throw new MlsException("Commit does not match the expected join epoch.");
            }
        } else {
            if (expectedEpoch >= 0 && current.epoch() != expectedEpoch) {
                throw new MlsException("Local state does not match the expected epoch.");
            }
            if (!current.groupId().equals(groupId)) throw new MlsException("Group state does not match the commit.");
            if (commit.epoch() != current.epoch() + 1) throw new MlsException("Commit epoch is stale or skips an epoch.");
            if (!MessageDigest.isEqual(current.hash(), commit.previousEpochHash())) {
                throw new MlsException("Commit does not extend the current epoch.");
            }
            if (!current.isMember(commit.committerId())) throw new MlsException("Commit signer was not a member.");
            for (UUID removed : commit.removed()) {
                if (!current.isMember(removed)) throw new MlsException("Removed member was not in the group.");
            }
            for (UUID added : commit.added()) {
                if (current.isMember(added)) throw new MlsException("Added member already existed.");
            }
            TreeMap<UUID, MlsKeyPackage> expected = new TreeMap<>(current.members());
            commit.removed().forEach(expected::remove);
            for (UUID added : commit.added()) expected.put(added, roster.get(added));
            if (!sameRoster(expected, roster)) throw new MlsException("Commit roster does not match the transition.");
        }
        MlsCommit.Welcome welcome = commit.welcomes().get(identity.userId());
        if (welcome == null) throw new MlsException("Commit has no welcome for this account.");
        byte[] secret = openWelcome(identity, commit, welcome);
        byte[] hash = epochHash(groupId, commit.epoch(), commit.previousEpochHash(), roster);
        return new MlsGroupState(groupId, commit.epoch(), secret, hash, roster);
    }

    public static MlsApplicationMessage encrypt(MlsGroupState state, MlsIdentity sender, byte[] plaintext)
            throws MlsException {
        if (state == null || sender == null || plaintext == null) throw new MlsException("Message data is required.");
        if (!state.isMember(sender.userId())) throw new MlsException("Sender is not a group member.");
        if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT) {
            throw new MlsException("Group messages must contain 1 to " + MAX_PLAINTEXT + " bytes.");
        }
        UUID messageId = UUID.randomUUID();
        byte[] key = MlsCodec.hkdf(state.secret(), MlsCodec.uuidBytes(state.groupId()),
                MlsCodec.label(APP_KEY, state.groupId(), state.epoch(), sender.userId(), messageId), 32);
        byte[] nonce = MlsCodec.randomBytes(12);
        byte[] aad = MlsCodec.label(APP_AAD, state.groupId(), state.epoch(), sender.userId(), messageId);
        byte[] ciphertext = MlsCodec.gcm(true, key, nonce, aad, plaintext);
        MlsApplicationMessage draft = new MlsApplicationMessage(state.groupId(), state.epoch(),
                sender.userId(), messageId, nonce, ciphertext, new byte[64]);
        return new MlsApplicationMessage(state.groupId(), state.epoch(), sender.userId(), messageId,
                nonce, ciphertext, sender.sign(draft.signedPayload()));
    }

    public static byte[] decrypt(MlsGroupState state, MlsIdentity recipient, UUID groupId, byte[] encodedMessage)
            throws MlsException {
        if (state == null || recipient == null || groupId == null) throw new MlsException("Message data is required.");
        MlsApplicationMessage message = MlsApplicationMessage.decode(encodedMessage);
        if (!message.groupId().equals(groupId) || !state.groupId().equals(groupId)) {
            throw new MlsException("Message belongs to another group.");
        }
        if (!state.isMember(recipient.userId())) throw new MlsException("Recipient is not a group member.");
        if (message.epoch() != state.epoch()) throw new MlsException("Message belongs to a stale or future epoch.");
        MlsKeyPackage sender = state.members().get(message.senderId());
        if (sender == null) throw new MlsException("Message sender is not a group member.");
        if (!MlsCodec.verify(sender.identityKey(), message.signedPayload(), message.signature())) {
            throw new MlsException("Message sender signature is invalid.");
        }
        if (state.hasSeen(message.messageId())) throw new MlsException("Message was already processed.");
        byte[] key = MlsCodec.hkdf(state.secret(), MlsCodec.uuidBytes(groupId),
                MlsCodec.label(APP_KEY, groupId, message.epoch(), message.senderId(), message.messageId()), 32);
        byte[] aad = MlsCodec.label(APP_AAD, groupId, message.epoch(), message.senderId(), message.messageId());
        byte[] plaintext = MlsCodec.gcm(false, key, message.nonce(), aad, message.ciphertext());
        if (!state.markSeen(message.messageId())) throw new MlsException("Message was already processed.");
        return plaintext;
    }

    private static Map<UUID, MlsCommit.Welcome> welcomes(Map<UUID, MlsKeyPackage> roster, UUID excluded,
                                                         UUID groupId, long epoch, byte[] secret) throws MlsException {
        Map<UUID, MlsCommit.Welcome> welcomes = new TreeMap<>();
        for (Map.Entry<UUID, MlsKeyPackage> entry : roster.entrySet()) {
            if (entry.getKey().equals(excluded)) continue;
            X25519PrivateKeyParameters ephemeral = new X25519PrivateKeyParameters(MlsCodec.RANDOM);
            byte[] shared = agreement(ephemeral, entry.getValue().initPublicKey());
            byte[] key = MlsCodec.hkdf(shared, MlsCodec.uuidBytes(groupId),
                    MlsCodec.label(WELCOME_KEY, groupId, epoch, entry.getKey()), 32);
            byte[] nonce = MlsCodec.randomBytes(12);
            byte[] aad = MlsCodec.label(WELCOME_AAD, groupId, epoch, entry.getKey());
            welcomes.put(entry.getKey(), new MlsCommit.Welcome(ephemeral.generatePublicKey().getEncoded(),
                    nonce, MlsCodec.gcm(true, key, nonce, aad, secret)));
        }
        return Map.copyOf(welcomes);
    }

    private static byte[] openWelcome(MlsIdentity identity, MlsCommit commit, MlsCommit.Welcome welcome)
            throws MlsException {
        byte[] shared = agreement(identity.initKey(),
                new X25519PublicKeyParameters(welcome.ephemeralKey(), 0));
        byte[] key = MlsCodec.hkdf(shared, MlsCodec.uuidBytes(commit.groupId()),
                MlsCodec.label(WELCOME_KEY, commit.groupId(), commit.epoch(), identity.userId()), 32);
        byte[] aad = MlsCodec.label(WELCOME_AAD, commit.groupId(), commit.epoch(), identity.userId());
        byte[] secret = MlsCodec.gcm(false, key, welcome.nonce(), aad, welcome.ciphertext());
        if (secret.length != 32) throw new MlsException("Welcome contains an invalid epoch secret.");
        return secret;
    }

    private static byte[] agreement(X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters publicKey)
            throws MlsException {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(privateKey);
        byte[] shared = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(publicKey, shared, 0);
        if (MlsCodec.isZero(shared)) throw new MlsException("Invalid X25519 agreement.");
        return shared;
    }

    private static byte[] epochHash(UUID groupId, long epoch, byte[] previousHash,
                                    Map<UUID, MlsKeyPackage> roster) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(EPOCH_DOMAIN);
                MlsCodec.writeUuid(output, groupId);
                output.writeLong(epoch);
                MlsCodec.writeBytes(output, previousHash, 32);
                output.writeInt(roster.size());
                for (Map.Entry<UUID, MlsKeyPackage> entry : roster.entrySet()) {
                    MlsKeyPackage keyPackage = entry.getValue();
                    MlsCodec.writeUuid(output, entry.getKey());
                    MlsCodec.writeString(output, keyPackage.deviceId());
                    MlsCodec.writeBytes(output, keyPackage.identityKey(), 32);
                    MlsCodec.writeBytes(output, keyPackage.initKey(), 32);
                }
            }
            return MlsCodec.sha256(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not compute the epoch hash.", exception);
        }
    }

    private static List<MlsCommit.Member> memberList(Map<UUID, MlsKeyPackage> roster) {
        List<MlsCommit.Member> members = new ArrayList<>(roster.size());
        roster.forEach((userId, keyPackage) -> members.add(new MlsCommit.Member(userId, keyPackage)));
        return List.copyOf(members);
    }

    private static boolean sameRoster(Map<UUID, MlsKeyPackage> left, Map<UUID, MlsKeyPackage> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (UUID userId : left.keySet()) {
            if (!left.get(userId).sameAs(right.get(userId))) return false;
        }
        return true;
    }
}
