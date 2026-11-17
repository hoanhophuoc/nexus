package com.nexus.server.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

    /** The batch this suite publishes: one MLS row, which needs no recipient lookup. */
    private static final OutboxPublisher.MlsOutboxRow MLS_ROW = new OutboxPublisher.MlsOutboxRow(
            new UUID(0L, 1L), new UUID(0L, 2L), new UUID(0L, 3L), "mls.message", new UUID(0L, 4L),
            3, "application", new byte[]{1, 2, 3}, Instant.parse("2026-11-16T10:00:00Z"), 7);

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

    @Test
    void anMlsRowTheMapperCannotEncodeIsAbandonedInsteadOfTheBatch() {
        Harness harness = new Harness();
        // An encoder failure is the one way this catch can run: every value in the envelope comes
        // from a database column the mapper already knew how to write.
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
        Harness harness = new Harness();
        when(harness.mapper.writeValueAsString(any())).thenReturn("{}");
        RedisConnectionFailureException outage = new RedisConnectionFailureException("redis is down");
        doThrow(new IllegalArgumentException("the payload is too large")).doThrow(outage)
                .when(harness.redis).execute(any(RedisCallback.class));

        // The batch is retried row by row after the first refusal; an outage on that retry means
        // Redis is unreachable, so the batch rolls back instead of blaming the row's payload.
        assertThatThrownBy(harness.publisher()::publishPending).isSameAs(outage);
        verify(harness.named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }

    /** The publisher with every collaborator a mock, serving one MLS row and nothing else. */
    private static final class Harness {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ObjectMapper mapper = mock(ObjectMapper.class);
        private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

        private Harness() {
            doAnswer(invocation -> OutboxPublisher.PENDING_MLS_SQL.equals(invocation.getArgument(0))
                    ? List.of(MLS_ROW)
                    : List.of())
                    .when(jdbc).query(anyString(), any(RowMapper.class));
        }

        private OutboxPublisher publisher() {
            return new OutboxPublisher(jdbc, named, redis, mapper, new SimpleMeterRegistry(), transactions);
        }
    }
}
