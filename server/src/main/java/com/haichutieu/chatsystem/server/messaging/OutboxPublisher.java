package com.haichutieu.chatsystem.server.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxPublisher {
    static final String CHANNEL = "chat_v1:message-events";
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public OutboxPublisher(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${chat.messaging.outbox-poll-ms:100}")
    @Transactional
    public void publishPending() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT o.event_id, o.conversation_id, o.message_id, o.recipient_user_id, o.event_type,
                       m.sender_id, m.content, m.created_at, m.deleted_for_everyone_at
                FROM chat_v1.message_outbox o
                JOIN chat_v1.message m ON m.id = o.message_id
                WHERE o.published_at IS NULL
                ORDER BY o.created_at, o.event_id
                LIMIT 100 FOR UPDATE OF o SKIP LOCKED
                """, OutboxPublisher::map, new Object[]{});
        for (OutboxRow row : rows) {
            List<UUID> targets = row.recipientId() == null
                    ? jdbc.query("""
                        SELECT user_id FROM chat_v1.conversation_member
                        WHERE conversation_id = ? AND left_at IS NULL ORDER BY user_id
                        """, (rs, index) -> rs.getObject(1, UUID.class), row.conversationId())
                    : List.of(row.recipientId());
            redis.convertAndSend(CHANNEL, encode(envelope(row, targets)));
            jdbc.update("UPDATE chat_v1.message_outbox SET published_at = clock_timestamp() WHERE event_id = ?",
                    row.eventId());
        }
        publishMlsPending();
    }

    private void publishMlsPending() {
        List<MlsOutboxRow> rows = jdbc.query("""
                SELECT o.event_id, o.conversation_id, o.mls_message_id, o.event_type,
                       m.sender_id, m.epoch, m.kind, m.ciphertext, m.created_at
                FROM chat_v1.message_outbox o
                JOIN chat_v1.mls_message m ON m.id = o.mls_message_id
                WHERE o.published_at IS NULL
                ORDER BY o.created_at, o.event_id
                LIMIT 100 FOR UPDATE OF o SKIP LOCKED
                """, (rs, row) -> new MlsOutboxRow(rs.getObject("event_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("mls_message_id", UUID.class),
                rs.getString("event_type"), rs.getObject("sender_id", UUID.class), rs.getLong("epoch"),
                rs.getString("kind"), rs.getBytes("ciphertext"),
                rs.getTimestamp("created_at").toInstant().toString()));
        for (MlsOutboxRow row : rows) {
            // only members whose join epoch covers this ciphertext receive the event
            List<UUID> targets = jdbc.query("""
                    SELECT user_id FROM chat_v1.conversation_member
                    WHERE conversation_id = ? AND left_at IS NULL AND joined_epoch <= ?
                    ORDER BY user_id
                    """, (rs, index) -> rs.getObject(1, UUID.class), row.conversationId(), row.epoch());
            redis.convertAndSend(CHANNEL, encode(mlsEnvelope(row, targets)));
            jdbc.update("UPDATE chat_v1.message_outbox SET published_at = clock_timestamp() WHERE event_id = ?",
                    row.eventId());
        }
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
        payload.put("ciphertext", Base64.getEncoder().encodeToString(row.ciphertext()));
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
            message.put("createdAt", row.messageCreatedAt());
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
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("deleted_for_everyone_at") == null ? null
                        : rs.getTimestamp("deleted_for_everyone_at").toInstant().toString());
    }

    private record OutboxRow(UUID eventId, UUID conversationId, UUID messageId, UUID recipientId,
                             String eventType, UUID senderId, String content, String messageCreatedAt, String deletedAt) { }

    private record MlsOutboxRow(UUID eventId, UUID conversationId, UUID messageId, String eventType,
                                UUID senderId, long epoch, String kind, byte[] ciphertext,
                                String createdAt) { }
}
