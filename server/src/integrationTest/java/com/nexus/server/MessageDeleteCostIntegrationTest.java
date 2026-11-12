package com.nexus.server;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting one message must find the outbox events that reference it through an index, not by
 * scanning every event ever written. The fixture writes one event per message spread over many
 * conversations, so the outbox table is the same size as the message table while a single
 * conversation still holds a small slice of it; without the indexes the foreign-key cascade scanned
 * the whole table (20.96 ms for a single row against 400k events on the perf database).
 */
@Testcontainers
@SpringBootTest
class MessageDeleteCostIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(MessageDeleteCostIntegrationTest.class);

    private static final int CONVERSATIONS = 100;
    private static final int MESSAGES_PER_CONVERSATION = 1_000;
    private static final int MESSAGES = CONVERSATIONS * MESSAGES_PER_CONVERSATION;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletingAMessageFindsItsOutboxEventsThroughTheIndex() {
        UUID sender = createUser("outbox-cost-sender");
        List<UUID> conversations = new ArrayList<>();
        for (int index = 0; index < CONVERSATIONS; index++) {
            UUID conversation = createConversation(sender, createUser("outbox-cost-partner-" + index));
            insertMessages(conversation, sender);
            conversations.add(conversation);
        }
        jdbc.update("""
                INSERT INTO nexus.message_outbox
                    (event_id, conversation_id, message_id, event_type, published_at)
                SELECT gen_random_uuid(), m.conversation_id, m.id, 'message.created', now()
                FROM nexus.message m
                """);
        jdbc.execute("ANALYZE nexus.message");
        jdbc.execute("ANALYZE nexus.message_outbox");

        assertThat(indexesOf("message_outbox")).contains("message_outbox_message_idx",
                "message_outbox_conversation_idx", "message_outbox_mls_message_idx");
        assertThat(indexesOf("conversation")).contains("conversation_direct_low_idx");

        UUID conversation = conversations.getFirst();
        UUID target = jdbc.queryForObject("""
                SELECT id FROM nexus.message WHERE conversation_id = ? AND conversation_sequence = 1
                """, UUID.class, conversation);
        assertThat(outboxCount()).isEqualTo(MESSAGES);
        assertThat(plan("SELECT 1 FROM nexus.message_outbox WHERE message_id = ?", target))
                .as("the cascade looks up outbox rows by message_id, so it must seek that index")
                .contains("message_outbox_message_idx");
        assertThat(plan("SELECT 1 FROM nexus.message_outbox WHERE conversation_id = ?", conversation))
                .as("deleting a conversation must not scan the outbox")
                .contains("message_outbox_conversation_idx");

        long startedAt = System.nanoTime();
        jdbc.update("DELETE FROM nexus.message WHERE id = ?", target);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        LOG.info("deleted one message out of {} with {} outbox events in {} ms",
                MESSAGES, MESSAGES, elapsedMillis);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM nexus.message_outbox WHERE message_id = ?", Long.class, target))
                .isZero();
        assertThat(outboxCount()).isEqualTo(MESSAGES - 1);
        assertThat(elapsedMillis)
                .as("deleting one message out of %d with %d outbox events took %d ms",
                        MESSAGES, MESSAGES, elapsedMillis)
                .isLessThan(500);
    }

    private void insertMessages(UUID conversation, UUID sender) {
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                SELECT gen_random_uuid(), ?, ?, sequence, ? || '-' || sequence, 'payload'
                FROM generate_series(1, ?) AS sequence
                """, conversation, sender, conversation.toString(), MESSAGES_PER_CONVERSATION);
    }

    private List<String> indexesOf(String table) {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'nexus' AND tablename = ?",
                String.class, table);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM nexus.message_outbox", Long.class);
    }

    private String plan(String sql, Object argument) {
        return String.join("\n", jdbc.queryForList("EXPLAIN (FORMAT JSON) " + sql, String.class, argument));
    }

    private UUID createConversation(UUID first, UUID second) {
        UUID conversationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, created_by, direct_low_id, direct_high_id)
                VALUES (?, 'direct', ?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))
                """, conversationId, first, first, second, first, second);
        return conversationId;
    }

    private UUID createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, DATE '1990-01-01', 'undisclosed', 'unused')
                """, id, username, username, username, username + "@example.com",
                username + "@example.com", "test");
        return id;
    }
}
