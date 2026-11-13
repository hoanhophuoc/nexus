package com.nexus.server.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;
import java.util.Objects;

@Component
public class OutboxPublisher {
    static final String CHANNEL = "nexus:message-events";
    private static final byte[] CHANNEL_BYTES = CHANNEL.getBytes(StandardCharsets.UTF_8);
    /** Package-private so the plan test can explain the exact statement the fan-out runs. */
    static final String PENDING_MLS_SQL = """
            SELECT o.event_id, o.conversation_id, o.mls_message_id, o.event_type,
                   m.sender_id, m.epoch, m.kind, m.ciphertext, m.created_at
            FROM nexus.message_outbox o
            JOIN nexus.mls_message m ON m.id = o.mls_message_id
            WHERE o.published_at IS NULL
            ORDER BY o.created_at, o.event_id
            LIMIT 100 FOR UPDATE OF o SKIP LOCKED
            """;

    private static final String ACTIVE_MEMBERS_SQL = """
            SELECT conversation_id, user_id FROM nexus.conversation_member
            WHERE conversation_id IN (:ids) AND left_at IS NULL
            ORDER BY conversation_id, user_id
            """;

    private static final String MEMBERS_WITH_EPOCH_SQL = """
            SELECT conversation_id, user_id, joined_epoch FROM nexus.conversation_member
            WHERE conversation_id IN (:ids) AND left_at IS NULL
            ORDER BY conversation_id, user_id
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE nexus.message_outbox SET published_at = clock_timestamp()
            WHERE event_id IN (:ids)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Timer publishTimer;
    private final Counter publishedEvents;
    private final AtomicLong batchRows = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxPublisher(JdbcTemplate jdbc, NamedParameterJdbcTemplate named,
                           StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.named = named;
        this.redis = redis;
        this.mapper = mapper;
        this.publishTimer = Timer.builder("nexus.outbox.publish")
                .description("Time to fan out one outbox batch to Redis")
                .register(meters);
        this.publishedEvents = Counter.builder("nexus.outbox.published")
                .description("Outbox events published to Redis")
                .register(meters);
        Gauge.builder("nexus.outbox.batch_rows", batchRows, AtomicLong::get)
                .description("Outbox rows handled by the last poll (capped at the batch limit)")
                .register(meters);
        Gauge.builder("nexus.outbox.oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .description("Age of the oldest row in the last published batch")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${chat.messaging.outbox-poll-ms:100}")
    @Transactional
    public void publishPending() {
        long started = System.nanoTime();
        List<OutboxRow> rows = jdbc.query("""
                SELECT o.event_id, o.conversation_id, o.message_id, o.recipient_user_id, o.event_type,
                       m.sender_id, m.content, m.created_at, m.deleted_for_everyone_at
                FROM nexus.message_outbox o
                JOIN nexus.message m ON m.id = o.message_id
                WHERE o.published_at IS NULL
                ORDER BY o.created_at, o.event_id
                LIMIT 100 FOR UPDATE OF o SKIP LOCKED
                """, OutboxPublisher::map, new Object[]{});
        List<MlsOutboxRow> mlsRows = publishMlsPending();
        long handled = rows.size() + mlsRows.size();
        batchRows.set(handled);
        if (handled == 0) {
            oldestAgeSeconds.set(0);
            return;
        }

        // One membership lookup and one Redis round trip per batch instead of per event.
        Map<UUID, List<UUID>> members = activeMembers(conversationIds(rows, mlsRows));
        Map<UUID, List<MemberEpoch>> mlsMembers = membersWithEpoch(conversationIds(rows, mlsRows));
        List<byte[]> payloads = new ArrayList<>(rows.size() + mlsRows.size());
        List<UUID> messageEventIds = new ArrayList<>(rows.size());
        List<UUID> mlsEventIds = new ArrayList<>(mlsRows.size());
        Instant now = Instant.now();
        long oldest = 0;
        for (OutboxRow row : rows) {
            List<UUID> targets = row.recipientId() == null
                    ? members.getOrDefault(row.conversationId(), List.of())
                    : List.of(row.recipientId());
            payloads.add(encode(envelope(row, targets)).getBytes(StandardCharsets.UTF_8));
            messageEventIds.add(row.eventId());
            oldest = Math.max(oldest, ageSeconds(row.createdAt(), now));
        }
        for (MlsOutboxRow row : mlsRows) {
            // only members whose join epoch covers this ciphertext receive the event
            List<UUID> targets = mlsMembers.getOrDefault(row.conversationId(), List.of()).stream()
                    .filter(member -> member.joinedEpoch() <= row.epoch())
                    .map(MemberEpoch::userId)
                    .toList();
            payloads.add(encode(mlsEnvelope(row, targets)).getBytes(StandardCharsets.UTF_8));
            mlsEventIds.add(row.eventId());
            oldest = Math.max(oldest, ageSeconds(row.createdAt(), now));
        }
        oldestAgeSeconds.set(oldest);

        // A pipelined callback borrows a dedicated Lettuce connection, i.e. a new TCP handshake
        // per batch. Publishing on the shared connection keeps one socket for all batches.
        redis.execute((RedisCallback<Object>) connection -> {
            for (byte[] payload : payloads) {
                connection.publish(CHANNEL_BYTES, payload);
            }
            return null;
        });
        markPublished(messageEventIds);
        markPublished(mlsEventIds);
        publishedEvents.increment(payloads.size());
        publishTimer.record(Duration.ofNanos(System.nanoTime() - started));
    }

    private List<MlsOutboxRow> publishMlsPending() {
        return jdbc.query(PENDING_MLS_SQL, (rs, row) -> new MlsOutboxRow(rs.getObject("event_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("mls_message_id", UUID.class),
                rs.getString("event_type"), rs.getObject("sender_id", UUID.class), rs.getLong("epoch"),
                rs.getString("kind"), rs.getBytes("ciphertext"),
                rs.getTimestamp("created_at").toInstant()));
    }

    private static List<UUID> conversationIds(List<OutboxRow> rows, List<MlsOutboxRow> mlsRows) {
        List<UUID> ids = new ArrayList<>(rows.size() + mlsRows.size());
        for (OutboxRow row : rows) {
            if (row.recipientId() == null && !ids.contains(row.conversationId())) {
                ids.add(row.conversationId());
            }
        }
        for (MlsOutboxRow row : mlsRows) {
            if (!ids.contains(row.conversationId())) ids.add(row.conversationId());
        }
        return ids;
    }

    private Map<UUID, List<UUID>> activeMembers(List<UUID> conversationIds) {
        Map<UUID, List<UUID>> members = new HashMap<>();
        if (conversationIds.isEmpty()) return members;
        named.query(ACTIVE_MEMBERS_SQL, new MapSqlParameterSource("ids", conversationIds),
                rs -> {
                    members.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>())
                            .add(rs.getObject(2, UUID.class));
                });
        return members;
    }

    private Map<UUID, List<MemberEpoch>> membersWithEpoch(List<UUID> conversationIds) {
        Map<UUID, List<MemberEpoch>> members = new HashMap<>();
        if (conversationIds.isEmpty()) return members;
        named.query(MEMBERS_WITH_EPOCH_SQL, new MapSqlParameterSource("ids", conversationIds),
                rs -> {
                    members.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>())
                            .add(new MemberEpoch(rs.getObject(2, UUID.class), rs.getInt(3)));
                });
        return members;
    }

    private void markPublished(List<UUID> eventIds) {
        if (eventIds.isEmpty()) return;
        named.update(MARK_PUBLISHED_SQL, new MapSqlParameterSource("ids", eventIds));
    }

    private static long ageSeconds(Instant createdAt, Instant now) {
        return Math.max(0, Duration.between(createdAt, now).toSeconds());
    }

    private Map<String, Object> mlsEnvelope(MlsOutboxRow row, List<UUID> targets) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", row.eventId());
        payload.put("cursor", row.messageId().toString());
        payload.put("type", row.eventType().equals("mls.commit") ? "MlsCommit" : "MlsMessage");
        payload.put("conversationId", row.conversationId());
        payload.put("id", row.messageId());
        payload.put("senderId", row.senderId());
        payload.put("epoch", row.epoch());
        payload.put("kind", row.kind());
        payload.put("ciphertext", java.util.Base64.getEncoder().encodeToString(row.ciphertext()));
        payload.put("createdAt", row.createdAt());
        return Map.of("targets", targets, "payload", payload);
    }

    private Map<String, Object> envelope(OutboxRow row, List<UUID> targets) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", row.eventId());
        payload.put("cursor", row.messageId().toString());
        if (row.eventType().equals("message.hidden")) payload.put("userId", row.recipientId());
        if (row.eventType().equals("message.created")) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", row.messageId());
            message.put("conversationId", row.conversationId());
            message.put("senderId", row.senderId());
            message.put("content", row.content());
            message.put("createdAt", row.createdAt());
            message.put("deletedForEveryoneAt", row.deletedAt());
            payload.put("message", message);
        } else {
            payload.put("conversationId", row.conversationId());
            payload.put("messageId", row.messageId());
        }
        payload.put("type", switch (row.eventType()) {
            case "message.created" -> "MessageCreated";
            case "message.deleted" -> "MessageDeleted";
            default -> "MessageHidden";
        });
        return Map.of("targets", targets, "payload", payload);
    }

    private String encode(Map<String, Object> event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode persisted message event.", exception);
        }
    }

    private static OutboxRow map(ResultSet rs, int row) throws SQLException {
        return new OutboxRow(rs.getObject("event_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getObject("message_id", UUID.class), rs.getObject("recipient_user_id", UUID.class),
                rs.getString("event_type"), rs.getObject("sender_id", UUID.class), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("deleted_for_everyone_at") == null ? null
                        : rs.getTimestamp("deleted_for_everyone_at").toInstant());
    }

    private record MemberEpoch(UUID userId, int joinedEpoch) { }

    private record OutboxRow(UUID eventId, UUID conversationId, UUID messageId, UUID recipientId,
                             String eventType, UUID senderId, String content, Instant createdAt,
                             Instant deletedAt) { }

    record MlsOutboxRow(UUID eventId, UUID conversationId, UUID messageId, String eventType,
                                UUID senderId, long epoch, String kind, byte[] ciphertext,
                                Instant createdAt) {
        @Override
        public boolean equals(Object other) {
            return other instanceof MlsOutboxRow row && Objects.equals(eventId, row.eventId)
                    && Objects.equals(conversationId, row.conversationId) && Objects.equals(messageId, row.messageId)
                    && Objects.equals(eventType, row.eventType) && Objects.equals(senderId, row.senderId)
                    && epoch == row.epoch && Objects.equals(kind, row.kind)
                    && Arrays.equals(ciphertext, row.ciphertext) && Objects.equals(createdAt, row.createdAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, conversationId, messageId, eventType, senderId, epoch, kind,
                    Arrays.hashCode(ciphertext), createdAt);
        }

        @Override
        public String toString() {
            return "MlsOutboxRow[" + eventId + ", epoch=" + epoch + ", kind=" + kind + "]";
        }
    }
}
