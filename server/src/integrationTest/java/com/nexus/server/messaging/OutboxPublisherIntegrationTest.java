package com.nexus.server.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.dao.DataAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fan-out had no test at all: it was assumed correct because the rows are simple. These drive
 * the batch the way the scheduler does and check both sides of it — who each event is addressed to,
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
    private static final UUID AHEAD = id(5);

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
    private PlatformTransactionManager transactions;
    @Autowired
    private OutboxPublisher publisher;

    @Test
    void sendsACreatedMessageToEveryMemberThatIsStillThere() throws Exception {
        UUID conversation = id(101);
        UUID message = id(201);
        UUID event = id(401);
        group(conversation, SENDER, MEMBER, DEPARTED);
        left(conversation, DEPARTED);
        message(message, conversation, SENDER);
        messageEvent(event, conversation, message, "message.created");

        withSubscriber(published -> {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(type(envelope)).isEqualTo("MessageCreated");
            assertThat(targets(envelope))
                    .as("every member still in the conversation receives the message")
                    .containsExactlyInAnyOrder(SENDER, MEMBER);

            publisher.publishPending();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("an already published batch is not sent twice")
                    .isNull();
        });
    }

    @Test
    void sendsADeletedMessageToTheSameAudienceWithoutTheBody() throws Exception {
        UUID conversation = id(102);
        UUID message = id(202);
        UUID event = id(402);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        messageEvent(event, conversation, message, "message.deleted");

        withSubscriber(published -> {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(type(envelope)).isEqualTo("MessageDeleted");
            assertThat(envelope.get("payload").get("messageId").asString())
                    .isEqualTo(message.toString());
            assertThat(envelope.get("payload").get("message"))
                    .as("a deletion carries no message body").isNull();
            assertThat(targets(envelope))
                    .as("the deletion is addressed like the message was")
                    .containsExactlyInAnyOrder(SENDER, MEMBER);
        });
    }

    @Test
    void sendsAHiddenMessageOnlyToThePersonWhoHidIt() throws Exception {
        UUID conversation = id(103);
        UUID message = id(203);
        UUID event = id(403);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        hidden(event, conversation, message, MEMBER);

        withSubscriber(published -> {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(type(envelope)).isEqualTo("MessageHidden");
            assertThat(envelope.get("payload").get("userId").asString()).isEqualTo(MEMBER.toString());
            assertThat(targets(envelope))
                    .as("hiding a message concerns one person, not the whole conversation")
                    .containsExactly(MEMBER);
        });
    }

    @Test
    void sendsMlsCiphertextOnlyToTheEpochItWasWrittenFor() throws Exception {
        UUID conversation = id(104);
        UUID mlsMessage = id(304);
        UUID event = id(404);
        group(conversation, SENDER);
        member(conversation, MEMBER, 1);
        member(conversation, LATE_JOINER, 2);
        mlsMessage(mlsMessage, conversation, SENDER, 1, "application");
        mls(event, conversation, mlsMessage, "mls.message");

        withSubscriber(published -> {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(type(envelope)).isEqualTo("MlsMessage");
            assertThat(envelope.get("payload").get("epoch").asString()).isEqualTo("1");
            assertThat(targets(envelope))
                    .as("a member who joined after this epoch cannot read the ciphertext")
                    .containsExactlyInAnyOrder(SENDER, MEMBER);
        });
    }

    @Test
    void sendsAnMlsCommitOnlyToTheEpochsItAppliesTo() throws Exception {
        UUID conversation = id(105);
        UUID mlsCommit = id(305);
        UUID event = id(405);
        group(conversation, SENDER);
        member(conversation, MEMBER, 1);
        member(conversation, LATE_JOINER, 2);
        member(conversation, AHEAD, 3);
        mlsMessage(mlsCommit, conversation, SENDER, 2, "commit");
        mls(event, conversation, mlsCommit, "mls.commit");

        withSubscriber(published -> {
            publisher.publishPending();

            JsonNode envelope = awaitEvent(published, event);
            assertThat(type(envelope)).isEqualTo("MlsCommit");
            assertThat(envelope.get("payload").get("kind").asString()).isEqualTo("commit");
            assertThat(targets(envelope))
                    .as("the commit is unreadable to a member who joins after it")
                    .containsExactlyInAnyOrder(SENDER, MEMBER, LATE_JOINER);
        });
    }

    @Test
    void publishesAMessageRowAndAnMlsRowInTheSamePoll() throws Exception {
        UUID conversation = id(107);
        UUID message = id(207);
        UUID mlsMessage = id(307);
        UUID createdEvent = id(407);
        UUID mlsEvent = id(408);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        mlsMessage(mlsMessage, conversation, SENDER, 1, "application");
        messageEvent(createdEvent, conversation, message, "message.created");
        mls(mlsEvent, conversation, mlsMessage, "mls.message");

        withSubscriber(published -> {
            publisher.publishPending();

            Map<UUID, JsonNode> batch = awaitEvents(published, createdEvent, mlsEvent);
            assertThat(type(batch.get(createdEvent))).isEqualTo("MessageCreated");
            assertThat(type(batch.get(mlsEvent))).isEqualTo("MlsMessage");
            assertThat(targets(batch.get(createdEvent)))
                    .as("both pending queries feed the one batch")
                    .containsExactlyInAnyOrder(SENDER, MEMBER);
            assertThat(targets(batch.get(mlsEvent))).containsExactlyInAnyOrder(SENDER, MEMBER);

            publisher.publishPending();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("the batch marks both rows, so nothing is sent again")
                    .isNull();
        });
    }

    @Test
    void keepsBothRowsPendingAndCountsEveryFailedAttempt() throws Exception {
        UUID conversation = id(106);
        UUID message = id(206);
        UUID mlsMessage = id(306);
        UUID messageEvent = id(409);
        UUID mlsEvent = id(410);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        mlsMessage(mlsMessage, conversation, SENDER, 1, "application");
        messageEvent(messageEvent, conversation, message, "message.created");
        mls(mlsEvent, conversation, mlsMessage, "mls.message");

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicBoolean redisDown = new AtomicBoolean(true);
        OutboxPublisher failing = new OutboxPublisher(jdbc, named, failingWhile(redis, redisDown),
                mapper, meters, transactions);

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThatThrownBy(failing::publishPending)
                    .as("failure " + attempt + " reaches the scheduler instead of being swallowed")
                    .isInstanceOf(RedisConnectionFailureException.class);
        }
        assertThat(attempts(messageEvent)).as("the message row counts each failed publish")
                .isEqualTo(2);
        assertThat(attempts(mlsEvent)).as("the MLS row counts with it").isEqualTo(2);
        assertThat(meters.find("nexus.outbox.publish_failures").counter().count())
                .as("each failed batch is visible as a metric").isEqualTo(2.0);
        assertThat(meters.find("nexus.outbox.max_attempts").gauge().value())
                .as("so is the highest attempt count a row has reached").isEqualTo(2.0);

        for (int attempt = 3; attempt <= 4; attempt++) {
            assertThatThrownBy(failing::publishPending)
                    .isInstanceOf(RedisConnectionFailureException.class);
        }

        assertThat(attempts(messageEvent)).as("four failures leave four attempts on the row")
                .isEqualTo(4);
        assertThat(attempts(mlsEvent)).isEqualTo(4);
        assertThat(meters.find("nexus.outbox.max_attempts").gauge().value()).isEqualTo(4.0);
        assertThat(publishedAt(messageEvent)).as("an unpublished row is what the next poll retries")
                .isNull();
        assertThat(publishedAt(mlsEvent)).isNull();

        redisDown.set(false);

        withSubscriber(published -> {
            failing.publishPending();

            assertThat(awaitEvents(published, messageEvent, mlsEvent))
                    .as("the retry delivers the whole batch").hasSize(2);
            assertThat(attempts(messageEvent)).as("a successful publish adds no attempt")
                    .isEqualTo(4);
            assertThat(attempts(mlsEvent)).isEqualTo(4);
            assertThat(meters.find("nexus.outbox.max_attempts").gauge().value())
                    .as("the gauge clears, so an alert on it resolves once Redis is back").isZero();
        });
    }

    @Test
    void stopsPollingARowThatReachedTheCap() throws Exception {
        UUID conversation = id(109);
        UUID message = id(209);
        UUID mlsMessage = id(309);
        UUID messageEvent = id(411);
        UUID mlsEvent = id(412);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        mlsMessage(mlsMessage, conversation, SENDER, 1, "application");
        messageEvent(messageEvent, conversation, message, "message.created");
        mls(mlsEvent, conversation, mlsMessage, "mls.message");

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        LettuceConnectionFactory unreachable =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        unreachable.afterPropertiesSet();
        try {
            StringRedisTemplate noRedis = new StringRedisTemplate(unreachable);
            noRedis.afterPropertiesSet();
            OutboxPublisher failing = new OutboxPublisher(jdbc, named, noRedis, mapper, meters,
                    transactions);

            for (int attempt = 1; attempt <= OutboxPublisher.MAX_ATTEMPTS; attempt++) {
                assertThatThrownBy(failing::publishPending)
                        .as("failure " + attempt + " is reported")
                        .isInstanceOf(RedisConnectionFailureException.class);
            }
            assertThat(attempts(messageEvent)).as("the message row stops at the cap")
                    .isEqualTo(OutboxPublisher.MAX_ATTEMPTS);
            assertThat(attempts(mlsEvent)).as("so does the MLS row")
                    .isEqualTo(OutboxPublisher.MAX_ATTEMPTS);
            assertThat(meters.find("nexus.outbox.abandoned").counter().count())
                    .as("both abandoned rows are counted for an operator").isEqualTo(2.0);

            // Capped rows are no longer selected, so a dead Redis stops being an error every 100 ms.
            assertThatCode(failing::publishPending).doesNotThrowAnyException();
            assertThat(attempts(messageEvent))
                    .as("the sixth poll does not even select, let alone count, the capped rows")
                    .isEqualTo(OutboxPublisher.MAX_ATTEMPTS);
        } finally {
            unreachable.destroy();
        }

        // The cap is per row: an event queued afterwards is published even while these two sit capped.
        UUID fresh = id(417);
        UUID freshMessage = id(215);
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, 2, 'fresh', 'hello')
                """, freshMessage, conversation, SENDER);
        messageEvent(fresh, conversation, freshMessage, "message.created");

        withSubscriber(published -> {
            publisher.publishPending();

            assertThat(awaitEvent(published, fresh))
                    .as("a row under the cap is still published").isNotNull();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("an abandoned row is never retried again").isNull();
            assertThat(publishedAt(messageEvent)).isNull();
            assertThat(publishedAt(mlsEvent)).isNull();
        });
    }

    @Test
    void abandonsOnlyTheRowItCannotEncode() throws Exception {
        UUID conversation = id(111);
        UUID good = id(211);
        UUID bad = id(212);
        UUID goodEvent = id(413);
        UUID badEvent = id(414);
        group(conversation, SENDER, MEMBER);
        message(good, conversation, SENDER);
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, 2, 'poison', 'POISON')
                """, bad, conversation, SENDER);
        messageEvent(goodEvent, conversation, good, "message.created");
        messageEvent(badEvent, conversation, bad, "message.created");

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxPublisher isolated = new OutboxPublisher(jdbc, named, redis, failingOn("POISON"),
                meters, transactions);

        withSubscriber(published -> {
            isolated.publishPending();

            assertThat(awaitEvent(published, goodEvent))
                    .as("one unencodable row does not fail the batch").isNotNull();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("the row that cannot be encoded is not sent").isNull();
            assertThat(attempts(badEvent)).as("it is abandoned instead of retried")
                    .isEqualTo(OutboxPublisher.MAX_ATTEMPTS);
            assertThat(publishedAt(badEvent)).isNull();
            assertThat(publishedAt(goodEvent)).isNotNull();
            assertThat(meters.find("nexus.outbox.abandoned").counter().count())
                    .as("and reported for an operator").isEqualTo(1.0);

            isolated.publishPending();
            assertThat(published.poll(1, TimeUnit.SECONDS))
                    .as("the next poll leaves it alone").isNull();
        });
    }

    @Test
    void skipsRowsAnotherTransactionHoldsLockedInsteadOfWaitingForThem() throws Exception {
        UUID conversation = id(112);
        UUID message = id(213);
        UUID event = id(415);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        messageEvent(event, conversation, message, "message.created");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService locker = Executors.newSingleThreadExecutor();
        Future<?> holds = locker.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForList("""
                    SELECT event_id FROM nexus.message_outbox WHERE published_at IS NULL FOR UPDATE
                    """, UUID.class);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS))
                .as("another transaction holds the pending row").isTrue();
        try {
            withSubscriber(published -> {
                ExecutorService poller = Executors.newSingleThreadExecutor();
                try {
                    Future<?> poll = poller.submit(publisher::publishPending);
                    // Without SKIP LOCKED the poll waits on the lock and never returns in time.
                    poll.get(3, TimeUnit.SECONDS);
                    assertThat(published.poll(500, TimeUnit.MILLISECONDS))
                            .as("the locked row is skipped, not published").isNull();
                } finally {
                    poller.shutdownNow();
                }
            });
        } finally {
            release.countDown();
            holds.get(10, TimeUnit.SECONDS);
            locker.shutdownNow();
        }

        withSubscriber(published -> {
            publisher.publishPending();

            assertThat(awaitEvent(published, event))
                    .as("the row is published once the competing lock is gone").isNotNull();
        });
    }

    @Test
    void resendsTheEventWhenTheMarkAfterThePublishFails() throws Exception {
        UUID conversation = id(113);
        UUID message = id(216);
        UUID event = id(416);
        group(conversation, SENDER, MEMBER);
        message(message, conversation, SENDER);
        messageEvent(event, conversation, message, "message.created");

        creatingFailingMark();
        try {
            withSubscriber(published -> {
                assertThatThrownBy(publisher::publishPending)
                        .as("the publish went out but the row could not be marked")
                        .isInstanceOf(DataAccessException.class);
                awaitEvent(published, event);
                assertThat(publishedAt(event)).as("the row stays pending for the retry").isNull();
                assertThat(attempts(event)).isEqualTo(1);

                droppingFailingMark();
                publisher.publishPending();

                assertThat(awaitEvent(published, event))
                        .as("at-least-once: the retry re-sends the event the subscriber already saw")
                        .isNotNull();
                assertThat(publishedAt(event)).isNotNull();
            });
        } finally {
            droppingFailingMark();
        }
    }

    @Test
    void givesEachRowToOnlyOneOfTwoConcurrentPublishers() throws Exception {
        UUID conversation = id(108);
        int rows = 120;
        group(conversation, SENDER, MEMBER);
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                SELECT gen_random_uuid(), ?, ?, s, 'k' || s, 'hello'
                FROM generate_series(1, ?) AS s
                """, conversation, SENDER, rows);
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, message_id, event_type)
                SELECT gen_random_uuid(), ?, m.id, 'message.created'
                FROM nexus.message m WHERE m.conversation_id = ?
                """, conversation, conversation);
        Set<UUID> seeded = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT event_id FROM nexus.message_outbox WHERE conversation_id = ?",
                UUID.class, conversation));

        withSubscriber(published -> {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService polls = Executors.newFixedThreadPool(2);
            try {
                for (int poll = 0; poll < 2; poll++) {
                    polls.execute(() -> {
                        try {
                            start.await();
                            publisher.publishPending();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                start.countDown();
                polls.shutdown();
                assertThat(polls.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                polls.shutdownNow();
            }

            // A poll may legitimately skip the rows the other one holds; drain the rest.
            for (int drain = 0; drain < 3; drain++) {
                publisher.publishPending();
            }

            Map<UUID, Integer> deliveries = new LinkedHashMap<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                String payload = published.poll(1, TimeUnit.SECONDS);
                if (payload == null) {
                    if (deliveries.size() == seeded.size()) break;
                    continue;
                }
                UUID event = UUID.fromString(
                        mapper.readTree(payload).get("payload").get("eventId").asString());
                deliveries.merge(event, 1, Integer::sum);
            }

            assertThat(deliveries.keySet()).as("every pending row is published")
                    .isEqualTo(seeded);
            assertThat(deliveries.values()).as("no row is published twice").containsOnly(1);
        });
    }

    private interface Scenario {
        void run(BlockingQueue<String> published) throws Exception;
    }

    /**
     * Fails the way an unreachable Redis does until {@code down} is cleared, so one publisher can be
     * driven through an outage and the recovery after it, with one set of meters watching both.
     */
    /** Mapper that cannot encode the payload carrying {@code content}, like a payload Jackson rejects. */
    private ObjectMapper failingOn(String content) {
        ObjectMapper poisoned = mock(ObjectMapper.class);
        when(poisoned.writeValueAsString(any())).thenAnswer(call -> {
            if (call.getArgument(0).toString().contains(content)) throw new Unencodable(content);
            return mapper.writeValueAsString(call.getArgument(0));
        });
        return poisoned;
    }

    /** Fails the mark step while it changes no attempts, i.e. never for the retry counter. */
    private void creatingFailingMark() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION nexus.fail_outbox_mark() RETURNS trigger AS $$
                BEGIN
                    IF NEW.attempts = OLD.attempts THEN RAISE EXCEPTION 'mark failed'; END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER outbox_mark_fails BEFORE UPDATE ON nexus.message_outbox
                FOR EACH ROW EXECUTE FUNCTION nexus.fail_outbox_mark()
                """);
    }

    private void droppingFailingMark() {
        jdbc.execute("DROP TRIGGER IF EXISTS outbox_mark_fails ON nexus.message_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS nexus.fail_outbox_mark()");
    }

    private static StringRedisTemplate failingWhile(StringRedisTemplate redis, AtomicBoolean down) {
        return new StringRedisTemplate(redis.getConnectionFactory()) {
            @Override
            public <T> T execute(RedisCallback<T> action) {
                if (down.get()) throw new RedisConnectionFailureException("redis is down");
                return super.execute(action);
            }
        };
    }

    private void withSubscriber(Scenario scenario) throws Exception {
        BlockingQueue<String> published = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis.getConnectionFactory());
        container.addMessageListener((message, pattern) ->
                        published.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(OutboxPublisher.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        try {
            awaitSubscriber(published);
            scenario.run(published);
        } finally {
            container.destroy();
        }
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
        return awaitEvents(published, event).get(event);
    }

    /** Collects the named events from one batch without dropping the others in it. */
    private Map<UUID, JsonNode> awaitEvents(BlockingQueue<String> published, UUID... events)
            throws InterruptedException {
        Set<UUID> pending = new LinkedHashSet<>(List.of(events));
        Map<UUID, JsonNode> envelopes = new LinkedHashMap<>();
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            String payload = published.poll(200, TimeUnit.MILLISECONDS);
            if (payload == null) continue;
            JsonNode envelope = mapper.readTree(payload);
            UUID event = UUID.fromString(envelope.get("payload").get("eventId").asString());
            if (pending.remove(event)) {
                envelopes.put(event, envelope);
            }
        }
        if (!pending.isEmpty()) {
            throw new AssertionError("No event " + pending + " was published within " + RECEIVE_TIMEOUT + ".");
        }
        return envelopes;
    }

    private static String type(JsonNode envelope) {
        return envelope.get("payload").get("type").asString();
    }

    private static Set<UUID> targets(JsonNode envelope) {
        Set<UUID> targets = new LinkedHashSet<>();
        for (JsonNode target : envelope.get("targets")) {
            targets.add(UUID.fromString(target.asString()));
        }
        return targets;
    }

    private int attempts(UUID event) {
        return jdbc.queryForObject("SELECT attempts FROM nexus.message_outbox WHERE event_id = ?",
                Integer.class, event);
    }

    private Timestamp publishedAt(UUID event) {
        return jdbc.queryForObject(
                "SELECT published_at FROM nexus.message_outbox WHERE event_id = ?",
                Timestamp.class, event);
    }

    private void group(UUID conversation, UUID... members) {
        jdbc.update("INSERT INTO nexus.conversation (id, type, name) VALUES (?, 'group', 'Team')",
                conversation);
        for (UUID member : members) {
            member(conversation, member, 0);
        }
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

    private void left(UUID conversation, UUID user) {
        jdbc.update("""
                UPDATE nexus.conversation_member SET left_at = clock_timestamp()
                WHERE conversation_id = ? AND user_id = ?
                """, conversation, user);
    }

    private void message(UUID message, UUID conversation, UUID sender) {
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, 1, ?, 'hello')
                """, message, conversation, sender, "k" + message.getLeastSignificantBits());
    }

    private void mlsMessage(UUID mlsMessage, UUID conversation, UUID sender, long epoch, String kind) {
        jdbc.update("""
                INSERT INTO nexus.mls_message (id, conversation_id, sender_id, epoch, kind, ciphertext)
                VALUES (?, ?, ?, ?, ?, '\\x00')
                """, mlsMessage, conversation, sender, epoch, kind);
    }

    private void messageEvent(UUID event, UUID conversation, UUID message, String eventType) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox (event_id, conversation_id, message_id, event_type)
                VALUES (?, ?, ?, ?)
                """, event, conversation, message, eventType);
    }

    private void hidden(UUID event, UUID conversation, UUID message, UUID recipient) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox
                    (event_id, conversation_id, message_id, recipient_user_id, event_type)
                VALUES (?, ?, ?, ?, 'message.hidden')
                """, event, conversation, message, recipient);
    }

    private void mls(UUID event, UUID conversation, UUID mlsMessage, String eventType) {
        jdbc.update("""
                INSERT INTO nexus.message_outbox
                    (event_id, conversation_id, mls_message_id, event_type)
                VALUES (?, ?, ?, ?)
                """, event, conversation, mlsMessage, eventType);
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }

    private static final class Unencodable extends JacksonException {
        Unencodable(String content) {
            super("Unable to encode " + content);
        }
    }
}
