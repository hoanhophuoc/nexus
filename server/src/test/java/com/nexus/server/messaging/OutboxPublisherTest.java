package com.nexus.server.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private static final UUID EVENT_ID = new UUID(0L, 1L);
    private static final UUID CONVERSATION_ID = new UUID(0L, 2L);
    private static final UUID MESSAGE_ID = new UUID(0L, 3L);
    private static final UUID SENDER_ID = new UUID(0L, 4L);
    private static final UUID RECIPIENT_ID = new UUID(0L, 5L);
    private static final Instant CREATED_AT = Instant.parse("2026-11-16T10:00:00Z");
    private static final ObjectMapper CONTRACT = new ObjectMapper();

    /** The batch the defensive-path tests publish: one MLS row, which needs no member lookup. */
    private static final OutboxPublisher.MlsOutboxRow MLS_ROW = mlsRow("mls.message");

    @Test
    void namesThreeAbandonedIdsAndCountsTheRest() {
        List<UUID> abandoned = IntStream.rangeClosed(1, 100).mapToObj(OutboxPublisherTest::id).toList();

        assertThat(OutboxPublisher.describe(abandoned))
                .as("an abandoned batch is up to 100 rows, so the line stays short")
                .isEqualTo(id(1) + ", " + id(2) + ", " + id(3) + " and 97 more");
    }

    @Test
    void listsEveryIdWhenTheyAllFitOnTheLine() {
        assertThat(OutboxPublisher.describe(List.of(id(7), id(8))))
                .isEqualTo(id(7) + ", " + id(8));
    }

    // The envelopes below are the wire contract: the desktop parses these same files in
    // MessageHistorySyncServiceTest and MlsGroupSyncServiceTest, so a renamed field fails here or
    // there instead of silently stopping realtime delivery.

    @Test
    void theMessageCreatedEnvelopeMatchesTheCheckedInContract() throws Exception {
        assertGolden("message-created", published(messageRow("message.created", null)));
    }

    @Test
    void theMessageDeletedEnvelopeMatchesTheCheckedInContract() throws Exception {
        assertGolden("message-deleted", published(messageRow("message.deleted", null)));
    }

    @Test
    void theMessageHiddenEnvelopeMatchesTheCheckedInContract() throws Exception {
        assertGolden("message-hidden", published(messageRow("message.hidden", RECIPIENT_ID)));
    }

    @Test
    void theMlsMessageEnvelopeMatchesTheCheckedInContract() throws Exception {
        assertGolden("mls-message", published(mlsRow("mls.message")));
    }

    @Test
    void theMlsCommitEnvelopeMatchesTheCheckedInContract() throws Exception {
        assertGolden("mls-commit", published(mlsRow("mls.commit")));
    }

    @Test
    void anMlsRowTheMapperCannotEncodeIsAbandonedInsteadOfTheBatch() {
        Harness harness = new Harness(mock(ObjectMapper.class), null, MLS_ROW);
        when(harness.mapper.writeValueAsString(any())).thenThrow(mock(JacksonException.class));

        harness.publisher().publishPending();

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(harness.named).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("reason")).isEqualTo("encode");
        assertThat(String.valueOf(params.getValue().getValue("ids")))
                .isEqualTo("[" + MLS_ROW.eventId() + "]");
        verifyNoInteractions(harness.redis);
    }

    @Test
    void aRedisOutageDuringTheRowByRowRetryIsRaisedNotCountedAsOneBadRow() {
        Harness harness = new Harness(mock(ObjectMapper.class), null, MLS_ROW);
        when(harness.mapper.writeValueAsString(any())).thenReturn("{}");
        RedisConnectionFailureException outage = new RedisConnectionFailureException("redis is down");
        doThrow(new IllegalArgumentException("the payload is too large")).doThrow(outage)
                .when(harness.redis).execute(any(RedisCallback.class));

        // The batch is retried row by row after the first refusal; an outage on that retry means
        // Redis is unreachable, so the batch rolls back instead of blaming the row's payload.
        assertThatThrownBy(harness.publisher()::publishPending).isSameAs(outage);
        verify(harness.named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    /** Publishes the one stubbed row and returns the bytes the batch actually sent to Redis. */
    private static String published(Object row) throws Exception {
        if (row instanceof OutboxPublisher.OutboxRow message) {
            Harness harness = new Harness(CONTRACT, message, null);
            harness.publisher().publishPending();
            return harness.capturedPayload();
        }
        Harness harness = new Harness(CONTRACT, null, (OutboxPublisher.MlsOutboxRow) row);
        harness.publisher().publishPending();
        return harness.capturedPayload();
    }

    private static OutboxPublisher.OutboxRow messageRow(String eventType, UUID recipientId) {
        return new OutboxPublisher.OutboxRow(EVENT_ID, CONVERSATION_ID, MESSAGE_ID, recipientId,
                eventType, SENDER_ID, "the payload the wire carries", CREATED_AT, null, 12);
    }

    private static OutboxPublisher.MlsOutboxRow mlsRow(String eventType) {
        return new OutboxPublisher.MlsOutboxRow(EVENT_ID, CONVERSATION_ID, MESSAGE_ID, eventType,
                SENDER_ID, 4, "mls.commit".equals(eventType) ? "commit" : "application",
                new byte[]{9, 8, 7, 6}, CREATED_AT, 12);
    }

    /**
     * Compares the bytes the publisher sends with the checked-in file. Regenerate with
     * {@code ./gradlew :server:test --tests '*OutboxPublisherTest*' -PcontractUpdate} and review the
     * diff: the desktop suites read the same files, so a change here is a change to both sides.
     */
    private static void assertGolden(String name, String encoded) throws IOException {
        Path directory = Path.of(System.getProperty("nexus.contractDir", "contract/realtime"));
        JsonNode envelope = CONTRACT.readTree(encoded);
        Path file = directory.resolve(name + ".json");
        if (Boolean.getBoolean("nexus.contract.update")) {
            Files.createDirectories(directory);
            Files.writeString(file, CONTRACT.writerWithDefaultPrettyPrinter().writeValueAsString(envelope)
                    + System.lineSeparator(), StandardCharsets.UTF_8);
            return;
        }
        // Compared as trees, not text: the envelope is built with Map.of, whose iteration order
        // changes between JVMs, so the bytes are not stable while the document is.
        assertThat(envelope)
                .as("%s no longer matches the publisher; regenerate it with -PcontractUpdate and review "
                        + "the diff, because the desktop parser tests read the same file", file)
                .isEqualTo(CONTRACT.readTree(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }

    /** The publisher with every collaborator a mock, serving one row and capturing what it sends. */
    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ObjectMapper mapper;
        private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        private final RedisConnection connection = mock(RedisConnection.class);

        private Harness(ObjectMapper mapper, OutboxPublisher.OutboxRow messageRow,
                        OutboxPublisher.MlsOutboxRow mlsRow) {
            this.mapper = mapper;
            doAnswer(invocation -> OutboxPublisher.PENDING_MLS_SQL.equals(invocation.getArgument(0))
                    ? (mlsRow == null ? List.of() : List.of(mlsRow))
                    : (messageRow == null ? List.of() : List.of(messageRow)))
                    .when(jdbc).query(anyString(), any(RowMapper.class));
            doAnswer(invocation -> ((RedisCallback<?>) invocation.getArgument(0)).doInRedis(connection))
                    .when(redis).execute(any(RedisCallback.class));
        }

        private OutboxPublisher publisher() {
            return new OutboxPublisher(jdbc, named, redis, mapper, new SimpleMeterRegistry(), transactions);
        }

        private String capturedPayload() {
            ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
            verify(connection).publish(any(byte[].class), payload.capture());
            return new String(payload.getValue(), StandardCharsets.UTF_8);
        }
    }
}
