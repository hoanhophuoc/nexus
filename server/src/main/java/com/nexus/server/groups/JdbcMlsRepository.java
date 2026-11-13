package com.nexus.server.groups;

import com.nexus.server.identity.UserNotFoundException;
import com.nexus.server.messaging.IdempotencyConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Routes opaque MLS bytes only: the server never holds group keys and cannot decrypt these payloads.
 * Epochs advance with compare-and-set on the conversation row so replayed or out-of-order commits
 * cannot reuse an epoch.
 */
@Repository
public class JdbcMlsRepository implements MlsRepository {

    /**
     * The page cursor is a (created_at, id) pair, and no message predates the epoch, so a first page
     * binds this pair instead of the request appending a comparison to the statement.
     */
    private static final Timestamp FIRST_PAGE_CREATED_AT = Timestamp.from(Instant.EPOCH);
    private static final UUID FIRST_PAGE_ID = new UUID(0L, 0L);

    static final String MESSAGES_SQL = """
            SELECT id, epoch, kind, ciphertext, created_at
            FROM nexus.mls_message
            WHERE conversation_id = ? AND epoch >= ?
              AND (created_at, id) > (?, ?)
            ORDER BY created_at, id LIMIT ?
            """;

    private static final String CURSOR_CREATED_AT_SQL = """
            SELECT created_at FROM nexus.mls_message WHERE conversation_id = ? AND id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcMlsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void submitCommit(UUID actorId, UUID groupId, long expectedEpoch, byte[] commit) {
        long currentEpoch = lockGroup(groupId);
        long joinedEpoch = requireMemberEpoch(actorId, groupId);
        if (joinedEpoch > currentEpoch) {
            throw new MlsConflictException("The group is awaiting the commit that adds this member.");
        }
        if (currentEpoch != expectedEpoch) {
            throw new MlsConflictException("The MLS epoch is stale.");
        }
        String commitHash = sha256(commit);
        if (exists(groupId, commitHash)) {
            throw new MlsConflictException("The MLS commit was already submitted.");
        }
        if (currentEpoch == Long.MAX_VALUE) {
            throw new MlsConflictException("The MLS epoch cannot advance further.");
        }
        UUID messageId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO nexus.mls_message
                        (id, conversation_id, epoch, sender_id, kind, ciphertext, idempotency_key)
                    VALUES (?, ?, ?, ?, 'commit', ?, ?)
                    """, messageId, groupId, currentEpoch + 1, actorId, commit, commitHash);
        } catch (DuplicateKeyException exception) {
            throw new MlsConflictException("The MLS commit was already submitted.");
        }
        jdbc.update("UPDATE nexus.conversation SET current_epoch = ? WHERE id = ?",
                currentEpoch + 1, groupId);
        queueEvent(messageId, groupId, "mls.commit");
    }

    @Override
    @Transactional
    public MlsMessageView sendMessage(UUID actorId, UUID groupId, long epoch, String idempotencyKey,
                                      byte[] ciphertext) {
        long currentEpoch = lockGroup(groupId);
        long joinedEpoch = requireMemberEpoch(actorId, groupId);
        if (joinedEpoch > currentEpoch) {
            throw new MlsConflictException("The group is awaiting the commit that adds this member.");
        }
        if (currentEpoch != epoch) {
            throw new MlsConflictException("The MLS epoch is stale.");
        }
        List<MlsMessageView> previous = jdbc.query("""
                SELECT id, epoch, kind, ciphertext, created_at
                FROM nexus.mls_message
                WHERE conversation_id = ? AND sender_id = ? AND idempotency_key = ?
                """, JdbcMlsRepository::map, groupId, actorId, idempotencyKey);
        if (!previous.isEmpty()) {
            MlsMessageView existing = previous.getFirst();
            if (!Arrays.equals(existing.ciphertext(), ciphertext)) {
                throw new IdempotencyConflictException();
            }
            return existing;
        }
        try {
            MlsMessageView saved = jdbc.query("""
                    INSERT INTO nexus.mls_message
                        (id, conversation_id, epoch, sender_id, kind, ciphertext, idempotency_key)
                    VALUES (?, ?, ?, ?, 'application', ?, ?)
                    RETURNING id, epoch, kind, ciphertext, created_at
                    """, JdbcMlsRepository::map, UUID.randomUUID(), groupId, currentEpoch, actorId,
                    ciphertext, idempotencyKey).getFirst();
            queueEvent(saved.id(), groupId, "mls.message");
            return saved;
        } catch (DuplicateKeyException exception) {
            throw new IdempotencyConflictException();
        }
    }

    @Override
    public MlsMessagePage messages(UUID actorId, UUID groupId, UUID after, int limit) {
        long joinedEpoch = requireMemberEpoch(actorId, groupId);
        Timestamp cursorCreatedAt = FIRST_PAGE_CREATED_AT;
        UUID cursorId = FIRST_PAGE_ID;
        if (after != null) {
            cursorCreatedAt = jdbc.query(CURSOR_CREATED_AT_SQL,
                    (rs, row) -> rs.getTimestamp("created_at"), groupId, after)
                    .stream().findFirst().orElseThrow(UserNotFoundException::new);
            cursorId = after;
        }
        List<MlsMessageView> found = jdbc.query(MESSAGES_SQL, JdbcMlsRepository::map,
                groupId, joinedEpoch, cursorCreatedAt, cursorId, limit + 1);
        boolean more = found.size() > limit;
        List<MlsMessageView> items = List.copyOf(found.subList(0, Math.min(found.size(), limit)));
        return new MlsMessagePage(items, more ? items.getLast().id().toString() : null);
    }

    @Override
    @Transactional
    public void storeRecoveryPackage(UUID actorId, UUID groupId, byte[] encryptedPackage) {
        requireMemberEpoch(actorId, groupId);
        jdbc.update("""
                INSERT INTO nexus.mls_recovery_package (conversation_id, user_id, encrypted_package)
                VALUES (?, ?, ?)
                ON CONFLICT (conversation_id, user_id) DO UPDATE
                SET encrypted_package = EXCLUDED.encrypted_package, updated_at = clock_timestamp()
                """, groupId, actorId, encryptedPackage);
    }

    @Override
    public byte[] recoveryPackage(UUID actorId, UUID groupId) {
        requireMemberEpoch(actorId, groupId);
        return jdbc.query("""
                SELECT encrypted_package FROM nexus.mls_recovery_package
                WHERE conversation_id = ? AND user_id = ?
                """, (rs, row) -> rs.getBytes("encrypted_package"), groupId, actorId)
                .stream().findFirst()
                .orElseThrow(() -> new MlsNotFoundException("No recovery package is stored for this account."));
    }

    private long lockGroup(UUID groupId) {
        List<Long> epochs = jdbc.query("""
                SELECT current_epoch FROM nexus.conversation
                WHERE id = ? AND type = 'group' FOR UPDATE
                """, (rs, row) -> rs.getLong("current_epoch"), groupId);
        if (epochs.isEmpty()) {
            throw new UserNotFoundException();
        }
        return epochs.getFirst();
    }

    private long requireMemberEpoch(UUID userId, UUID groupId) {
        List<Long> epochs = jdbc.query("""
                SELECT joined_epoch FROM nexus.conversation_member
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, (rs, row) -> rs.getLong("joined_epoch"), groupId, userId);
        if (epochs.isEmpty()) {
            throw new UserNotFoundException();
        }
        return epochs.getFirst();
    }

    private boolean exists(UUID groupId, String idempotencyKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM nexus.mls_message
                               WHERE conversation_id = ? AND idempotency_key = ?)
                """, Boolean.class, groupId, idempotencyKey));
    }

    private void queueEvent(UUID mlsMessageId, UUID groupId, String eventType) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, mls_message_id, event_type)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), groupId, mlsMessageId, eventType);
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static MlsMessageView map(ResultSet rs, int row) throws SQLException {
        return new MlsMessageView(rs.getObject("id", UUID.class), rs.getLong("epoch"),
                rs.getString("kind"), rs.getBytes("ciphertext"),
                rs.getTimestamp("created_at").toInstant());
    }
}
