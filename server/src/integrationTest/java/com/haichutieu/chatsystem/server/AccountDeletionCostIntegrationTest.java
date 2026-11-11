package com.haichutieu.chatsystem.server;

import com.haichutieu.chatsystem.server.administration.AdminService;
import com.haichutieu.chatsystem.server.identity.SessionIdentity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting one account must cost the rows that account owns, not a scan of the message table.
 * The fixture gives the deleted account one conversation of its own and spreads 199,000 further
 * messages over conversations it never joined: the plan assertion fails if the cascade falls back
 * to a sequential scan, and the surviving rows prove the delete stayed inside its own history.
 */
@Testcontainers
@SpringBootTest
class AccountDeletionCostIntegrationTest {

    private static final int OTHER_SENDERS = 199;
    private static final int MESSAGES_PER_SENDER = 1000;

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

    @Autowired
    private AdminService admin;

    @Test
    void deletingAnAccountWithManyMessagesDoesNotScanTheMessageTable() {
        SessionIdentity actor = createAdmin();
        UUID target = createUser("delete-target");
        UUID partner = createUser("delete-partner");
        List<UUID> otherSenders = new ArrayList<>();
        for (int index = 0; index < OTHER_SENDERS; index += 1) {
            otherSenders.add(createUser("bulk-" + index));
        }

        UUID targetConversation = createConversation(target, partner);
        insertMessages(targetConversation, target, MESSAGES_PER_SENDER);
        String otherArray = otherSenders.stream().map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
        jdbc.update("""
                INSERT INTO chat_v1.conversation (id, type, created_by, direct_low_id, direct_high_id)
                SELECT gen_random_uuid(), 'direct', sender, LEAST(sender, ?::uuid), GREATEST(sender, ?::uuid)
                FROM unnest(?::uuid[]) AS sender
                """, partner, partner, otherArray);
        jdbc.update("""
                INSERT INTO chat_v1.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                SELECT gen_random_uuid(), owner.id, owner.created_by,
                       row_number() OVER (PARTITION BY owner.id), 'bulk-' || owner.id || '-' || seq,
                       'payload'
                FROM chat_v1.conversation owner
                CROSS JOIN generate_series(1, ?) AS seq
                WHERE owner.created_by = ANY(?::uuid[])
                """, MESSAGES_PER_SENDER, otherArray);
        jdbc.execute("ANALYZE chat_v1.message");

        long allMessages = (OTHER_SENDERS + 1L) * MESSAGES_PER_SENDER;
        assertThat(messageCount()).isEqualTo(allMessages);
        assertThat(messageCount(target)).isEqualTo(MESSAGES_PER_SENDER);
        // Falling back to a sequential scan is not the only failure mode: without this index the
        // planner happily reads the whole (conversation_id, sender_id, idempotency_key) index and
        // filters it, which is still O(table). The plan has to seek on the sender index itself.
        assertThat(messagePlan(target))
                .as("the cascade must seek messages by sender, not filter a wider index or scan")
                .contains("message_sender_idx");

        long startedAt = System.nanoTime();
        admin.deleteUser(actor, target);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(messageCount(target)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_v1.user_account WHERE id = ?",
                Integer.class, target)).isZero();
        assertThat(messageCount())
                .as("only the deleted account's own conversation may disappear")
                .isEqualTo((long) OTHER_SENDERS * MESSAGES_PER_SENDER);
        assertThat(elapsedMillis)
                .as("deleting an account with %d messages out of %d took %d ms",
                        MESSAGES_PER_SENDER, allMessages, elapsedMillis)
                .isLessThan(1_000);
    }

    private void insertMessages(UUID conversation, UUID sender, int count) {
        jdbc.update("""
                INSERT INTO chat_v1.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                SELECT gen_random_uuid(), ?, ?, seq, 'own-' || seq, 'payload'
                FROM generate_series(1, ?) AS seq
                """, conversation, sender, count);
    }

    private long messageCount() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_v1.message", Long.class);
    }

    private long messageCount(UUID sender) {
        return jdbc.queryForObject("SELECT count(*) FROM chat_v1.message WHERE sender_id = ?",
                Long.class, sender);
    }

    private String messagePlan(UUID sender) {
        List<String> plan = jdbc.queryForList(
                "EXPLAIN (FORMAT JSON) SELECT 1 FROM chat_v1.message WHERE sender_id = ?",
                String.class, sender);
        return String.join("\n", plan);
    }

    private UUID createConversation(UUID first, UUID second) {
        UUID conversationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_v1.conversation (id, type, created_by, direct_low_id, direct_high_id)
                VALUES (?, 'direct', ?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))
                """, conversationId, first, first, second, first, second);
        return conversationId;
    }

    private SessionIdentity createAdmin() {
        UUID id = createUser("admin-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("UPDATE chat_v1.user_account SET role = 'admin' WHERE id = ?", id);
        return new SessionIdentity(id, "admin");
    }

    private UUID createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_v1.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, DATE '1990-01-01', 'undisclosed', 'unused')
                """, id, username, username, username, username + "@example.com",
                username + "@example.com", "test");
        return id;
    }
}
