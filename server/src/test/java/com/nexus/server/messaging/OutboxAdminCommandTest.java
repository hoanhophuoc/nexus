package com.nexus.server.messaging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxAdminCommandTest {
    private final OutboxAdminService admin = mock(OutboxAdminService.class);

    @Test
    void listsAbandonedRowsWithTheReasonAndTheLastError() {
        UUID event = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(admin.abandoned(OutboxAdminCommand.LIST_LIMIT)).thenReturn(List.of(
                new OutboxAdminService.AbandonedEvent(event, "message.created",
                        Instant.parse("2026-11-14T10:15:30Z"), "retries", "redis is down")));

        assertThat(OutboxAdminCommand.execute("list", admin))
                .contains(event.toString(), "retries", "redis is down");
    }

    @Test
    void saysWhenNothingWasAbandoned() {
        when(admin.abandoned(OutboxAdminCommand.LIST_LIMIT)).thenReturn(List.of());

        assertThat(OutboxAdminCommand.execute("list", admin)).isEqualTo("No abandoned outbox events.");
    }

    @Test
    void requeuesEveryAbandonedRow() {
        when(admin.requeueAll()).thenReturn(3);

        assertThat(OutboxAdminCommand.execute("requeue", admin))
                .isEqualTo("Requeued 3 abandoned event(s).");
    }

    @Test
    void requeuesOnlyTheNamedRows() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
        when(admin.requeue(List.of(first, second))).thenReturn(2);

        assertThat(OutboxAdminCommand.execute("requeue:" + first + "," + second, admin))
                .isEqualTo("Requeued 2 of 2 event(s).");
    }

    @Test
    void refusesACommandItDoesNotKnow() {
        assertThatThrownBy(() -> OutboxAdminCommand.execute("drain", admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("use list, requeue or requeue:");
    }
}
