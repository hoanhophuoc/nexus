package com.nexus.server.messaging;

import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.RedisConnectionFailureException;
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
    /**
     * A failed row waits longer each time before its next attempt, so a Redis restart is ridden out
     * instead of burning every attempt in half a second. The cap bounds how far the doubling goes.
     */
    /** Both pending polls are package-private so the plan test explains the statements the fan-out runs. */
    static final String PENDING_MESSAGES_SQL = """
            SELECT o.event_id, o.conversation_id, o.message_id, o.recipient_user_id, o.event_type,
                   m.sender_id, m.content, m.created_at, m.deleted_for_everyone_at,
                   GREATEST(0, EXTRACT(EPOCH FROM clock_timestamp() - m.created_at))::bigint
                       AS age_seconds
            FROM nexus.message_outbox o
            JOIN nexus.message m ON m.id = o.message_id
            WHERE o.published_at IS NULL AND o.attempts < %d
              AND o.next_attempt_at <= clock_timestamp()
            ORDER BY o.created_at, o.event_id
            LIMIT 100 FOR UPDATE OF o SKIP LOCKED
            """.formatted(MAX_ATTEMPTS);

    static final String PENDING_MLS_SQL = """
            SELECT o.event_id, o.conversation_id, o.mls_message_id, o.event_type,
                   m.sender_id, m.epoch, m.kind, m.ciphertext, m.created_at,
                   GREATEST(0, EXTRACT(EPOCH FROM clock_timestamp() - m.created_at))::bigint
                       AS age_seconds
            FROM nexus.message_outbox o
            JOIN nexus.mls_message m ON m.id = o.mls_message_id
            WHERE o.published_at IS NULL AND o.attempts < %d
              AND o.next_attempt_at <= clock_timestamp()
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
            UPDATE nexus.message_outbox
            SET attempts = attempts + 1,
                next_attempt_at = clock_timestamp()
                    + power(2, attempts + 1) * interval '1 second'
                        -- spread replicas out: a quarter either way, so they do not retry in lockstep
                        * (0.75 + random() * 0.5)
            WHERE event_id IN (:ids)
            RETURNING event_id, attempts
            """;

    private static final String ABANDON_SQL = """
            UPDATE nexus.message_outbox
            SET attempts = %d, abandoned_at = clock_timestamp(),
                abandon_reason = :reason, last_error = :error
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
    private final Map<String, Counter> abandonedByReason;
    /** Long enough for a driver message, short enough that a repeated failure cannot bloat a row. */
    private static final int MAX_ERROR_CHARS = 500;
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
        this.abandonedByReason = Map.of(
                "encode", Counter.builder("nexus.outbox.abandoned").tag("reason", "encode")
                        .description("Outbox events given up on because they cannot be encoded")
                        .register(meters),
                "retries", Counter.builder("nexus.outbox.abandoned").tag("reason", "retries")
                        .description("Outbox events given up on after " + MAX_ATTEMPTS
                                + " failed publishes")
                        .register(meters));
        Gauge.builder("nexus.outbox.max_attempts", maxAttempts, AtomicLong::get)
                .description("Highest failed-publish count on a row in the last failed batch; "
                        + "0 once a batch publishes")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${chat.messaging.outbox-poll-ms:100}")
    public void publishPending() {
        List<UUID> attempted = new ArrayList<>();
        List<Abandon> abandons = new ArrayList<>();
        try {
            transactions.executeWithoutResult(status -> publishBatch(attempted, abandons));
        } catch (RuntimeException failure) {
            publishFailures.increment();
            // The batch's transaction rolled back and took its row locks with it, so the retry
            // counter has to be written afterwards; inside the batch it would roll back too.
            try {
                countAttempts(attempted, errorText("the publish failed:", failure), abandons);
            } catch (RuntimeException countFailure) {
                failure.addSuppressed(countFailure);
            }
            abandonAll(abandons);
            throw failure;
        }
        // Only now is the row state this decision describes committed: an abandonment written inside
        // the batch would be rolled back while its counter and log line were not.
        abandonAll(abandons);
    }

    private void publishBatch(List<UUID> attempted, List<Abandon> abandons) {
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
        List<Delivery> deliveries = new ArrayList<>(rows.size() + mlsRows.size());
        long oldest = 0;
        List<UUID> unencodable = new ArrayList<>();
        for (OutboxRow row : rows) {
            oldest = Math.max(oldest, row.ageSeconds());
            List<UUID> targets = row.recipientId() == null
                    ? members.getOrDefault(row.conversationId(), List.of())
                    : List.of(row.recipientId());
            try {
                deliveries.add(new Delivery(row.eventId(),
                        encode(envelope(row, targets)).getBytes(StandardCharsets.UTF_8)));
            } catch (IllegalStateException poison) {
                unencodable.add(row.eventId());
            }
        }
        for (MlsOutboxRow row : mlsRows) {
            oldest = Math.max(oldest, row.ageSeconds());
            // only members whose join epoch covers this ciphertext receive the event
            List<UUID> targets = mlsMembers.getOrDefault(row.conversationId(), List.of()).stream()
                    .filter(member -> member.joinedEpoch() <= row.epoch())
                    .map(MemberEpoch::userId)
                    .toList();
            try {
                deliveries.add(new Delivery(row.eventId(),
                        encode(mlsEnvelope(row, targets)).getBytes(StandardCharsets.UTF_8)));
            } catch (IllegalStateException poison) {
                unencodable.add(row.eventId());
            }
        }
        oldestAgeSeconds.set(oldest);

        // One unencodable row must not take its batch down with it: abandon it, publish the rest.
        if (!unencodable.isEmpty()) {
            abandons.add(new Abandon(unencodable, "encode", "the row cannot be encoded"));
        }
        if (deliveries.isEmpty()) return;
        List<UUID> eventIds = new ArrayList<>(deliveries.size());
        for (Delivery delivery : deliveries) eventIds.add(delivery.eventId());
        attempted.addAll(eventIds);

        int published = publish(deliveries, abandons);
        if (published > 0) publishedEvents.increment(published);
        publishTimer.record(Duration.ofNanos(System.nanoTime() - started));
    }

    /**
     * Publishes the batch in one round trip. When that fails, the rows are tried one at a time so a
     * payload Redis rejects on its own — too large, a value it will not accept — costs its own row
     * and not the rest of the batch. A connection failure is not worth retrying row by row: it means
     * Redis is unreachable, so it is rethrown and the caller rolls the batch back and counts every
     * row's attempt.
     */
    private int publish(List<Delivery> deliveries, List<Abandon> abandons) {
        try {
            send(deliveries);
        } catch (RedisConnectionFailureException outage) {
            throw outage;
        } catch (RuntimeException batchFailure) {
            return publishOneByOne(deliveries, batchFailure, abandons);
        }
        // Outside the catch on purpose: a failed mark is not a failed publish, and sending the batch
        // row by row would only deliver every event a second time.
        markPublished(eventIds(deliveries));
        // The failure gauge has to clear itself, or an alert on it never resolves.
        maxAttempts.set(0);
        return deliveries.size();
    }

    private int publishOneByOne(List<Delivery> deliveries, RuntimeException batchFailure,
                                List<Abandon> abandons) {
        List<Delivery> delivered = new ArrayList<>(deliveries.size());
        List<UUID> failed = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            try {
                send(List.of(delivery));
                delivered.add(delivery);
            } catch (RedisConnectionFailureException outage) {
                throw outage;
            } catch (RuntimeException rowFailure) {
                failed.add(delivery.eventId());
            }
        }
        markPublished(eventIds(delivered));
        if (failed.size() == deliveries.size()) throw batchFailure;
        countAttempts(failed, errorText("the row could not be published:", batchFailure), abandons);
        log.warn("Outbox published {} of {} row(s); {} will retry", delivered.size(),
                deliveries.size(), failed.size());
        return delivered.size();
    }

    /**
     * A pipelined callback borrows a dedicated Lettuce connection, i.e. a new TCP handshake per
     * batch. Publishing on the shared connection keeps one socket for all batches.
     */
    private void send(List<Delivery> deliveries) {
        redis.execute((RedisCallback<Object>) connection -> {
            for (Delivery delivery : deliveries) {
                connection.publish(CHANNEL_BYTES, delivery.payload());
            }
            return null;
        });
    }

    private static List<UUID> eventIds(List<Delivery> deliveries) {
        List<UUID> eventIds = new ArrayList<>(deliveries.size());
        for (Delivery delivery : deliveries) eventIds.add(delivery.eventId());
        return eventIds;
    }

    /** Why a row was given up on, for the operator who has to decide whether to requeue it. */
    private static String errorText(String prefix, RuntimeException failure) {
        String message = failure.getMessage();
        String text = message == null ? failure.getClass().getSimpleName() : message;
        return prefix + " " + text;
    }

    private List<MlsOutboxRow> publishMlsPending() {
        return jdbc.query(PENDING_MLS_SQL, (rs, row) -> new MlsOutboxRow(rs.getObject("event_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("mls_message_id", UUID.class),
                rs.getString("event_type"), rs.getObject("sender_id", UUID.class), rs.getLong("epoch"),
                rs.getString("kind"), rs.getBytes("ciphertext"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("age_seconds")));
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
    private void countAttempts(List<UUID> eventIds, String error, List<Abandon> abandons) {
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
            abandons.add(new Abandon(abandoned, "retries", error));
        }
    }

    private void abandonAll(List<Abandon> abandons) {
        for (Abandon decision : abandons) {
            abandon(decision.eventIds(), decision.reason(), decision.error());
        }
    }

    /**
     * Stops polling the rows for good and records why, so an operator can list them and decide
     * between a requeue and giving up on the events.
     */
    private void abandon(List<UUID> eventIds, String reason, String error) {
        String stored = error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS);
        named.update(ABANDON_SQL, new MapSqlParameterSource("ids", eventIds)
                .addValue("reason", reason).addValue("error", stored));
        abandonedByReason.get(reason).increment(eventIds.size());
        log.warn("Outbox abandoned {} event(s), reason={}, {}: {}", eventIds.size(), reason, stored,
                describe(eventIds));
    }

    /** A batch is up to 100 rows: name a few ids and count the rest, so one log line stays readable. */
    static String describe(List<UUID> eventIds) {
        int shown = Math.min(3, eventIds.size());
        String first = String.join(", ", eventIds.subList(0, shown).stream()
                .map(UUID::toString).toList());
        int rest = eventIds.size() - shown;
        return rest == 0 ? first : first + " and " + rest + " more";
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
                        : rs.getTimestamp("deleted_for_everyone_at").toInstant(),
                rs.getLong("age_seconds"));
    }

    private record MemberEpoch(UUID userId, int joinedEpoch) { }

    private record Attempt(UUID eventId, int attempts) { }

    /** An abandonment the batch decided on, applied once its transaction has committed. */
    private record Abandon(List<UUID> eventIds, String reason, String error) { }

    /** One event's encoded payload, kept with its id so it can be published on its own. */
    private record Delivery(UUID eventId, byte[] payload) { }

    private record OutboxRow(UUID eventId, UUID conversationId, UUID messageId, UUID recipientId,
                             String eventType, UUID senderId, String content, Instant createdAt,
                             Instant deletedAt, long ageSeconds) { }

    record MlsOutboxRow(UUID eventId, UUID conversationId, UUID messageId, String eventType,
                                UUID senderId, long epoch, String kind, byte[] ciphertext,
                                Instant createdAt, long ageSeconds) {
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
