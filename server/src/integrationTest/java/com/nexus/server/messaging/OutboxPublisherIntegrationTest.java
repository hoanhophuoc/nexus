package com.nexus.server.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fan-out had no test at all: it was assumed correct because the rows are simple. These drive
 * the batch the way the scheduler does and check both sides of it — who the event is addressed to,
 * and that every row is marked published so the next poll sends nothing. A row whose Redis publish
 * failed has to stay pending, because that is the only thing that makes the next poll retry it.
 */
@Testcontainers
@SpringBootTest
class OutboxPublisherIntegrationTest {

    private static final String PROBE = "subscriber-probe";
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);
    private static final UUID SENDER = id(1);
    private static final UUID MEMBER = id(2);
    private static final UUID LATE_JOINER = id(3);
    private static final UUID DEPARTED = id(4);

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
        // The scheduler polls every 100 ms, so it would publish the seeded rows before the listener
        // below is subscribed. Its only run then happens at startup, against an empty outbox.
        properties.add("chat.messaging.outbox-poll-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate named;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private OutboxPublisher publisher;

    @Test
    void sendsACreatedMessageToEveryMemberThatIsStillThere() throws Exception {
        UUID conversation = id(101);
        UUID message = id(201);
        UUID event = id(401);
        conversation(conversation);
        member(conversation, SENDER, 0);
        member(conversation, MEMBER, 0);
        member(conversation, DEPARTED, 0);
        leave(conversation, DEPARTED);
        message(message, conversation, SENDER, 1);
        created(event, conversation, message);

        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = subscribe(published);
        try {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(envelope.get("payload").get("type").asString()).isEqualTo("MessageCreated");
            assertThat(targets(envelope))
                    .as("every member still in the conversation receives the message")
                    .containsExactlyInAnyOrder(SENDER, MEMBER)
                    .doesNotContain(DEPARTED);
            assertThat(publishedAt(event)).as("the row is marked published").isNotNull();

            publisher.publishPending();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("an already published batch is not sent twice")
                    .isNull();
        } finally {
            container.destroy();
        }
    }

    @Test
    void sendsAHiddenMessageOnlyToThePersonWhoHidIt() throws Exception {
        UUID conversation = id(102);
        UUID message = id(202);
        UUID event = id(402);
        conversation(conversation);
        member(conversation, SENDER, 0);
        member(conversation, MEMBER, 0);
        message(message, conversation, SENDER, 1);
        hidden(event, conversation, message, MEMBER);

        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = subscribe(published);
        try {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(envelope.get("payload").get("type").asString()).isEqualTo("MessageHidden");
            assertThat(envelope.get("payload").get("userId").asString()).isEqualTo(MEMBER.toString());
            assertThat(targets(envelope))
                    .as("hiding a message concerns one person, not the whole conversation")
                    .containsExactly(MEMBER);
        } finally {
            container.destroy();
        }
    }

    @Test
    void sendsMlsCiphertextOnlyToTheEpochItWasWrittenFor() throws Exception {
        UUID conversation = id(103);
        UUID mlsMessage = id(303);
        UUID event = id(403);
        conversation(conversation);
        member(conversation, SENDER, 0);
        member(conversation, MEMBER, 1);
        member(conversation, LATE_JOINER, 2);
        mlsMessage(mlsMessage, conversation, SENDER, 1);
        mls(event, conversation, mlsMessage);

        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = subscribe(published);
        try {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(envelope.get("payload").get("type").asString()).isEqualTo("MlsMessage");
            assertThat(targets(envelope))
                    .as("a member who joined after this epoch cannot read the ciphertext")
                    .containsExactlyInAnyOrder(SENDER, MEMBER)
                    .doesNotContain(LATE_JOINER);
        } finally {
            container.destroy();
        }
    }

    @Test
    void leavesTheRowPendingWhenRedisIsUnreachable() throws Exception {
        UUID conversation = id(104);
        UUID message = id(204);
        UUID event = id(404);
        conversation(conversation);
        member(conversation, SENDER, 0);
        member(conversation, MEMBER, 0);
        message(message, conversation, SENDER, 1);
        created(event, conversation, message);

        LettuceConnectionFactory unreachable =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        unreachable.afterPropertiesSet();
        try {
            StringRedisTemplate noRedis = new StringRedisTemplate(unreachable);
            noRedis.afterPropertiesSet();
            OutboxPublisher failing = new OutboxPublisher(jdbc, named, noRedis, mapper,
                    new SimpleMeterRegistry());

            assertThatThrownBy(failing::publishPending)
                    .as("the failure reaches the scheduler instead of being swallowed")
                    .isInstanceOf(RedisConnectionFailureException.class);
        } finally {
            unreachable.destroy();
        }

        assertThat(publishedAt(event)).as("an unpublished row is what the next poll retries").isNull();

        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = subscribe(published);
        try {
            publisher.publishPending();

            assertThat(targets(awaitEvent(published, event)))
                    .as("the retry delivers the same batch")
                    .containsExactlyInAnyOrder(SENDER, MEMBER);
            assertThat(publishedAt(event)).isNotNull();
        } finally {
            container.destroy();
        }
    }

    private RedisMessageListenerContainer subscribe(BlockingQueue<String> published)
            throws InterruptedException {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis.getConnectionFactory());
        container.addMessageListener((message, pattern) ->
                        published.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(OutboxPublisher.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        awaitSubscriber(published);
        return container;
    }

    /** {@code start()} subscribes on another thread, and a publish before that is simply lost. */
    private void awaitSubscriber(BlockingQueue<String> published) throws InterruptedException {
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            redis.convertAndSend(OutboxPublisher.CHANNEL, PROBE);
            if (PROBE.equals(published.poll(200, TimeUnit.MILLISECONDS))) {
                published.clear();
                return;
            }
        }
        throw new AssertionError("The Redis listener never subscribed.");
    }

    private JsonNode awaitEvent(BlockingQueue<String> published, UUID event) throws InterruptedException {
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String payload = published.poll(200, TimeUnit.MILLISECONDS);
            if (payload == null) continue;
            JsonNode envelope = mapper.readTree(payload);
            if (event.toString().equals(envelope.get("payload").get("eventId").asString())) {
                return envelope;
            }
        }
        throw new AssertionError("No event " + event + " was published within " + RECEIVE_TIMEOUT + ".");
    }

    private static Set<UUID> targets(JsonNode envelope) {
        Set<UUID> targets = new LinkedHashSet<>();
        for (JsonNode target : envelope.get("targets")) {
            targets.add(UUID.fromString(target.asString()));
        }
        return targets;
    }

    private Timestamp publishedAt(UUID event) {
        return jdbc.queryForObject(
                "SELECT published_at FROM nexus.message_outbox WHERE event_id = ?",
                Timestamp.class, event);
    }

    private void conversation(UUID conversation) {
        jdbc.update("INSERT INTO nexus.conversation (id, type, name) VALUES (?, 'group', 'Team')",
                conversation);
    }

    private void member(UUID conversation, UUID user, long joinedEpoch) {
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, ?, ?, 'User', ?, ?, 'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                ON CONFLICT (id) DO NOTHING
                """, user, "user" + user.getLeastSignificantBits(),
                "user" + user.getLeastSignificantBits(),
                "user" + user.getLeastSignificantBits() + "@example.com",
                "user" + user.getLeastSignificantBits() + "@example.com");
        jdbc.update("""
                INSERT INTO nexus.conversation_member (conversation_id, user_id, joined_epoch)
                VALUES (?, ?, ?)
                """, conversation, user, joinedEpoch);
    }

    private void leave(UUID conversation, UUID user) {
        jdbc.update("""
                UPDATE nexus.conversation_member SET left_at = clock_timestamp()
                WHERE conversation_id = ? AND user_id = ?
                """, conversation, user);
    }

    private void message(UUID message, UUID conversation, UUID sender, long sequence) {
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, ?, ?, 'hello')
                """, message, conversation, sender, sequence, "k" + message.getLeastSignificantBits());
    }

    private void mlsMessage(UUID mlsMessage, UUID conversation, UUID sender, long epoch) {
        jdbc.update("""
                INSERT INTO nexus.mls_message (id, conversation_id, sender_id, epoch, kind, ciphertext)
                VALUES (?, ?, ?, ?, 'application', '\\x00')
                """, mlsMessage, conversation, sender, epoch);
    }

    private void created(UUID event, UUID conversation, UUID message) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, message_id, event_type)
                VALUES (?, ?, ?, 'message.created')
                """, event, conversation, message);
    }

    private void hidden(UUID event, UUID conversation, UUID message, UUID recipient) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox
                    (event_id, conversation_id, message_id, recipient_user_id, event_type)
                VALUES (?, ?, ?, ?, 'message.hidden')
                """, event, conversation, message, recipient);
    }

    private void mls(UUID event, UUID conversation, UUID mlsMessage) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox
                    (event_id, conversation_id, mls_message_id, event_type)
                VALUES (?, ?, ?, 'mls.message')
                """, event, conversation, mlsMessage);
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }
}
