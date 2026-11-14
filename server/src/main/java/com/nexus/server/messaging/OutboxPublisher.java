package com.nexus.server.messaging;

import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;
import java.util.Objects;

@Component
public class OutboxPublisher {
    static final String CHANNEL = "nexus:message-events";
    private static final byte[] CHANNEL_BYTES = CHANNEL.getBytes(StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    /**
     * How many publishes a row gets before it is abandoned: interpolated into both pending polls and
     * the abandon statement, so the number has one definition.
     */
    static final int MAX_ATTEMPTS = 5;
    /** Both pending polls are package-private so the plan test explains the statements the fan-out runs. */
    static final String PENDING_MESSAGES_SQL = """
            SELECT o.event_id, o.conversation_id, o.message_id, o.recipient_user_id, o.event_type,
                   m.sender_id, m.content, m.created_at, m.deleted_for_everyone_at
            FROM nexus.message_outbox o
            JOIN nexus.message m ON m.id = o.message_id
            WHERE o.published_at IS NULL AND o.attempts < %d
            ORDER BY o.created_at, o.event_id
            LIMIT 100 FOR UPDATE OF o SKIP LOCKED
            """.formatted(MAX_ATTEMPTS);

    static final String PENDING_MLS_SQL = """
            SELECT o.event_id, o.conversation_id, o.mls_message_id, o.event_type,
                   m.sender_id, m.epoch, m.kind, m.ciphertext, m.created_at
            FROM nexus.message_outbox o
            JOIN nexus.mls_message m ON m.id = o.mls_message_id
            WHERE o.published_at IS NULL AND o.attempts < %d
            ORDER BY o.created_at, o.event_id
            LIMIT 100 FOR UPDATE OF o SKIP LOCKED
            """.formatted(MAX_ATTEMPTS);

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

    private static final String COUNT_ATTEMPT_SQL = """
            UPDATE nexus.message_outbox SET attempts = attempts + 1
            WHERE event_id IN (:ids)
            RETURNING event_id, attempts
            """;

    private static final String ABANDON_SQL = """
            UPDATE nexus.message_outbox SET attempts = %d
            WHERE event_id IN (:ids)
            """.formatted(MAX_ATTEMPTS);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Timer publishTimer;
    private final Counter publishedEvents;
    private final Counter publishFailures;
    private final Counter abandonedEvents;
    private final AtomicLong batchRows = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong maxAttempts = new AtomicLong();

    public OutboxPublisher(JdbcTemplate jdbc, NamedParameterJdbcTemplate named,
                           StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters,
                           PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.named = named;
        this.redis = redis;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactions);
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
        this.publishFailures = Counter.builder("nexus.outbox.publish_failures")
                .description("Outbox batches whose Redis publish failed")
                .register(meters);
        this.abandonedEvents = Counter.builder("nexus.outbox.abandoned")
                .description("Outbox events given up on: they could not be encoded, or failed "
                        + MAX_ATTEMPTS + " publishes")
                .register(meters);
        Gauge.builder("nexus.outbox.max_attempts", maxAttempts, AtomicLong::get)
                .description("Highest failed-publish count on a row in the last failed batch; "
                        + "0 once a batch publishes")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${chat.messaging.outbox-poll-ms:100}")
    public void publishPending() {
        List<UUID> attempted = new ArrayList<>();
        try {
            transactions.executeWithoutResult(status -> publishBatch(attempted));
        } catch (RuntimeException failure) {
            publishFailures.increment();
            // The batch's transaction rolled back and took its row locks with it, so the retry
            // counter has to be written afterwards; inside the batch it would roll back too.
            try {
                countAttempts(attempted);
            } catch (RuntimeException countFailure) {
                failure.addSuppressed(countFailure);
            }
            throw failure;
        }
    }

    private void publishBatch(List<UUID> attempted) {
        long started = System.nanoTime();
        List<OutboxRow> rows = jdbc.query(PENDING_MESSAGES_SQL, OutboxPublisher::map);
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
        List<UUID> unencodable = new ArrayList<>();
        for (OutboxRow row : rows) {
            oldest = Math.max(oldest, ageSeconds(row.createdAt(), now));
            List<UUID> targets = row.recipientId() == null
                    ? members.getOrDefault(row.conversationId(), List.of())
                    : List.of(row.recipientId());
            try {
                payloads.add(encode(envelope(row, targets)).getBytes(StandardCharsets.UTF_8));
            } catch (IllegalStateException poison) {
                unencodable.add(row.eventId());
                continue;
            }
            messageEventIds.add(row.eventId());
        }
        for (MlsOutboxRow row : mlsRows) {
            oldest = Math.max(oldest, ageSeconds(row.createdAt(), now));
            // only members whose join epoch covers this ciphertext receive the event
            List<UUID> targets = mlsMembers.getOrDefault(row.conversationId(), List.of()).stream()
                    .filter(member -> member.joinedEpoch() <= row.epoch())
                    .map(MemberEpoch::userId)
                    .toList();
            try {
                payloads.add(encode(mlsEnvelope(row, targets)).getBytes(StandardCharsets.UTF_8));
            } catch (IllegalStateException poison) {
                unencodable.add(row.eventId());
                continue;
            }
            mlsEventIds.add(row.eventId());
        }
        oldestAgeSeconds.set(oldest);

        // One unencodable row must not take its batch down with it: abandon it, publish the rest.
        if (!unencodable.isEmpty()) {
            named.update(ABANDON_SQL, new MapSqlParameterSource("ids", unencodable));
            reportAbandoned(unencodable, "because they cannot be encoded");
        }
        if (payloads.isEmpty()) return;
        List<UUID> eventIds = new ArrayList<>(messageEventIds);
        eventIds.addAll(mlsEventIds);
        attempted.addAll(eventIds);

        // A pipelined callback borrows a dedicated Lettuce connection, i.e. a new TCP handshake
        // per batch. Publishing on the shared connection keeps one socket for all batches.
        redis.execute((RedisCallback<Object>) connection -> {
            for (byte[] payload : payloads) {
                connection.publish(CHANNEL_BYTES, payload);
            }
            return null;
        });
        markPublished(eventIds);
        // The failure gauge has to clear itself, or an alert on it never resolves after an outage.
        maxAttempts.set(0);
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

    /**
     * Counts one failed publish per row, publishes the highest count seen, and reports the rows that
     * have now reached {@link #MAX_ATTEMPTS}: they stay in the table, unpublished, for an operator to
     * inspect, but neither pending poll selects them any more.
     */
    private void countAttempts(List<UUID> eventIds) {
        if (eventIds.isEmpty()) return;
        List<Attempt> counted = transactions.execute(status -> named.query(COUNT_ATTEMPT_SQL,
                new MapSqlParameterSource("ids", eventIds),
                (rs, row) -> new Attempt(rs.getObject("event_id", UUID.class), rs.getInt("attempts"))));
        if (counted.isEmpty()) return;
        maxAttempts.set(counted.stream().mapToLong(Attempt::attempts).max().orElse(0));

        List<UUID> abandoned = counted.stream()
                .filter(attempt -> attempt.attempts() >= MAX_ATTEMPTS)
                .map(Attempt::eventId)
                .toList();
        if (!abandoned.isEmpty()) {
            reportAbandoned(abandoned, "after " + MAX_ATTEMPTS + " failed publishes");
        }
    }

    private void reportAbandoned(List<UUID> eventIds, String reason) {
        abandonedEvents.increment(eventIds.size());
        log.warn("Outbox abandoned {} event(s) {}: {}", eventIds.size(), reason, describe(eventIds));
    }

    /** A batch is up to 100 rows: name a few ids and count the rest, so one log line stays readable. */
    static String describe(List<UUID> eventIds) {
        int shown = Math.min(3, eventIds.size());
        String first = String.join(", ", eventIds.subList(0, shown).stream()
                .map(UUID::toString).toList());
        int rest = eventIds.size() - shown;
        return rest == 0 ? first : first + " and " + rest + " more";
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

    private record Attempt(UUID eventId, int attempts) { }

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
