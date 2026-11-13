package com.nexus.server.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat list and the message history both page by cursor, and both used to build their cursor
 * comparison by concatenating a clause. The statements are now constant and bind a first-page
 * sentinel instead (a nil UUID, and sequence 0), so this test pins what the rewrite must not lose:
 * the list still reads {@code conversation_member_user_idx} rather than walking every conversation,
 * and the history still reads {@code message_conversation_cursor_idx} rather than the whole message
 * table. The outbox fan-out keeps reading its partial pending index.
 */
@Testcontainers
@SpringBootTest
class MessagingPlanIntegrationTest {

    private static final UUID VIEWER = new UUID(0L, 1L);
    private static final int CONVERSATIONS = 3_000;

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
    void chatListReadsTheMemberIndexInsteadOfEveryConversation() {
        seed();

        String plan = explain(JdbcMessagingRepository.CONVERSATIONS_SQL,
                "'" + VIEWER + "', '" + new UUID(0L, 0L) + "', 26");

        assertThat(plan)
                .as("the chat list must reach conversation_member_user_idx: %s", plan)
                .contains("conversation_member_user_idx")
                .doesNotContain("Seq Scan on conversation_member");
    }

    @Test
    void messageHistoryReadsTheCursorIndexInsteadOfEveryMessage() {
        seed();

        String plan = explain(JdbcMessagingRepository.MESSAGES_SQL,
                "'" + VIEWER + "', '" + VIEWER + "', 0, 26");

        assertThat(plan)
                .as("the history must reach message_conversation_cursor_idx: %s", plan)
                .contains("message_conversation_cursor_idx")
                .doesNotContain("Seq Scan on message ");
    }

    @Test
    void outboxFanOutReadsThePendingIndex() {
        seed();

        String plan = explain(OutboxPublisher.PENDING_MLS_SQL, "");

        assertThat(plan)
                .as("the fan-out must reach message_outbox_pending_idx: %s", plan)
                .contains("message_outbox_pending_idx");
    }

    /**
     * Plans a repository statement with the constants inlined: EXECUTE does not accept bind
     * parameters, and nothing here comes from a request.
     */
    private String explain(String sql, String arguments) {
        jdbc.execute("DEALLOCATE ALL");
        jdbc.execute("PREPARE plan_target AS " + numberedParameters(sql));
        String call = arguments.isBlank() ? "plan_target" : "plan_target(" + arguments + ")";
        String execute = "EXPLAIN (ANALYZE, FORMAT TEXT) EXECUTE " + call;
        return String.join("\n", jdbc.queryForList(execute, String.class));
    }

    private static String numberedParameters(String sql) {
        StringBuilder numbered = new StringBuilder(sql.length() + 8);
        int parameter = 1;
        for (char character : sql.toCharArray()) {
            if (character == '?') {
                numbered.append('$').append(parameter++);
            } else {
                numbered.append(character);
            }
        }
        return numbered.toString();
    }

    private void seed() {
        if (!jdbc.queryForList("SELECT 1 FROM nexus.conversation LIMIT 1", Integer.class).isEmpty()) return;

        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, 'viewer', 'viewer', 'Viewer', 'viewer@example.com', 'viewer@example.com',
                        'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                ON CONFLICT (id) DO NOTHING
                """, VIEWER);
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                SELECT gen_random_uuid(), 'user' || i, 'user' || i, 'User ' || i,
                       'user' || i || '@example.com', 'user' || i || '@example.com',
                       'addr', DATE '1990-01-01', 'undisclosed', 'unused'
                FROM generate_series(1, 40) AS i
                """);
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, name)
                SELECT gen_random_uuid(), 'group', 'group ' || i FROM generate_series(1, ?) AS i
                """, CONVERSATIONS);
        jdbc.update("""
                INSERT INTO nexus.conversation_member (conversation_id, user_id)
                SELECT c.id, u.id
                FROM (SELECT id, row_number() OVER () AS rn FROM nexus.conversation) c
                JOIN (SELECT id, row_number() OVER () AS rn FROM nexus.user_account) u
                  ON u.rn = 1 + (c.rn % 41)
                ON CONFLICT DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO nexus.conversation_member (conversation_id, user_id)
                SELECT c.id, ?
                FROM (SELECT id FROM nexus.conversation) c
                ON CONFLICT DO NOTHING
                """, VIEWER);
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                SELECT gen_random_uuid(), c.id, ?, s, 'k' || c.n || '-' || s, 'hello'
                FROM (SELECT id, row_number() OVER () AS n FROM nexus.conversation) c
                JOIN generate_series(1, 5) AS s ON TRUE
                """, VIEWER);
        jdbc.update("""
                INSERT INTO nexus.mls_message (id, conversation_id, sender_id, epoch, kind, ciphertext)
                SELECT gen_random_uuid(), c.id, ?, 1, 'application', '\\x00'
                FROM (SELECT id FROM nexus.conversation LIMIT 2000) c
                """, VIEWER);
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, mls_message_id, event_type)
                SELECT gen_random_uuid(), m.conversation_id, m.id, 'mls.message'
                FROM nexus.mls_message m
                """);
        for (String table : new String[]{"user_account", "conversation", "conversation_member", "message",
                "mls_message", "message_outbox"}) {
            jdbc.execute("ANALYZE nexus." + table);
        }
    }
}
