package com.nexus.server.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fan-out had no test at all: it was assumed correct because the rows are simple. It publishes to
 * Redis and only then marks the outbox rows published, and the marking step used to run as two
 * separate updates. This drives one batch containing a message event and an MLS event and checks both
 * sides of that: what reached Redis, and that every row is left published so the next poll is empty.
 */
@Testcontainers
@SpringBootTest
class OutboxPublisherIntegrationTest {

    private static final UUID SENDER = id(1);
    private static final UUID MEMBER = id(2);
    private static final UUID CONVERSATION = id(101);

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
    private StringRedisTemplate redis;
    @Autowired
    private OutboxPublisher publisher;

    @Test
    void publishesBothEventKindsAndMarksEveryRowPublished() throws Exception {
        seed();

        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        MessageListener listener = (message, pattern) ->
                published.add(new String(message.getBody(), StandardCharsets.UTF_8));
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis.getConnectionFactory());
        container.addMessageListener(listener, new ChannelTopic(OutboxPublisher.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        try {
            publisher.publishPending();

            String messageEvent = await(published);
            String mlsEvent = await(published);
            assertThat(messageEvent + mlsEvent)
                    .as("a message event and an MLS event both reach Redis")
                    .contains("MessageCreated")
                    .contains("MlsMessage");

            assertThat(unpublishedRows())
                    .as("both rows are marked published, so the next poll finds nothing")
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM nexus.message_outbox WHERE published_at IS NOT NULL",
                    Integer.class)).isEqualTo(2);

            publisher.publishPending();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("an already published batch is not sent twice")
                    .isNull();
        } finally {
            container.stop();
            container.destroy();
        }
    }

    private String await(BlockingQueue<String> published) throws InterruptedException {
        String payload = published.poll(10, TimeUnit.SECONDS);
        assertThat(payload).as("the fan-out published nothing within ten seconds").isNotNull();
        return payload;
    }

    private int unpublishedRows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM nexus.message_outbox WHERE published_at IS NULL", Integer.class);
    }

    private void seed() {
        UUID[] users = {SENDER, MEMBER};
        for (int index = 0; index < users.length; index++) {
            String name = "user" + index;
            jdbc.update("""
                    INSERT INTO nexus.user_account
                        (id, username, username_normalized, full_name, email, email_normalized, address,
                         birth_date, gender, password_hash)
                    VALUES (?, ?, ?, ?, ?, ?, 'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                    ON CONFLICT (id) DO NOTHING
                    """, users[index], name, name, "User " + index,
                    name + "@example.com", name + "@example.com");
        }
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, name) VALUES (?, 'group', 'Team')
                ON CONFLICT (id) DO NOTHING
                """, CONVERSATION);
        for (UUID user : new UUID[]{SENDER, MEMBER}) {
            jdbc.update("""
                    INSERT INTO nexus.conversation_member (conversation_id, user_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, CONVERSATION, user);
        }
        UUID messageId = id(201);
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, 1, 'k1', 'hello')
                ON CONFLICT (id) DO NOTHING
                """, messageId, CONVERSATION, SENDER);
        UUID mlsMessageId = id(301);
        jdbc.update("""
                INSERT INTO nexus.mls_message (id, conversation_id, sender_id, epoch, kind, ciphertext)
                VALUES (?, ?, ?, 1, 'application', '\\x00')
                ON CONFLICT (id) DO NOTHING
                """, mlsMessageId, CONVERSATION, SENDER);
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, message_id, event_type)
                VALUES (?, ?, ?, 'message.created')
                ON CONFLICT (event_id) DO NOTHING
                """, id(401), CONVERSATION, messageId);
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, mls_message_id, event_type)
                VALUES (?, ?, ?, 'mls.message')
                ON CONFLICT (event_id) DO NOTHING
                """, id(402), CONVERSATION, mlsMessageId);
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }
}
