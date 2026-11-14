package com.nexus.server.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The rescue path for rows the publisher has given up on. They are never polled again, so the only
 * thing that can deliver them is an operator putting them back — with the reason and the last error
 * that stopped them, because an unencodable payload will be abandoned again immediately.
 */
@Service
public class OutboxAdminService {
    private static final String LIST_SQL = """
            SELECT event_id, event_type, abandoned_at, abandon_reason, last_error
            FROM nexus.message_outbox
            WHERE published_at IS NULL AND abandoned_at IS NOT NULL
            ORDER BY abandoned_at, event_id
            LIMIT ?
            """;

    /** Requeueing clears every trace of the abandonment, so the row polls as if it were new. */
    private static final String REQUEUE_SET = """
            UPDATE nexus.message_outbox
            SET attempts = 0, next_attempt_at = clock_timestamp(), abandoned_at = NULL,
                abandon_reason = NULL, last_error = NULL
            WHERE published_at IS NULL AND abandoned_at IS NOT NULL
            """;

    private static final String REQUEUE_SQL = REQUEUE_SET + " AND event_id IN (:ids)";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public OutboxAdminService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public List<AbandonedEvent> abandoned(int limit) {
        return jdbc.query(LIST_SQL, (rs, row) -> new AbandonedEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getTimestamp("abandoned_at").toInstant(),
                rs.getString("abandon_reason"),
                rs.getString("last_error")), limit);
    }

    public int requeue(List<UUID> eventIds) {
        if (eventIds.isEmpty()) return 0;
        return named.update(REQUEUE_SQL, new MapSqlParameterSource("ids", eventIds));
    }

    public int requeueAll() {
        return jdbc.update(REQUEUE_SET);
    }

    public record AbandonedEvent(UUID eventId, String eventType, Instant abandonedAt, String reason,
                                 String lastError) { }
}
