package com.nexus.server.messaging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlsOutboxRowTest {
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-11-12T10:00:00Z");

    private static OutboxPublisher.MlsOutboxRow row(byte[] ciphertext) {
        return new OutboxPublisher.MlsOutboxRow(EVENT_ID, CONVERSATION_ID, MESSAGE_ID, "mls.message",
                SENDER_ID, 7, "application", ciphertext, CREATED_AT);
    }

    @Test
    void equalsComparesCiphertextByContent() {
        assertEquals(row(new byte[]{1, 2}), row(new byte[]{1, 2}));
        assertEquals(row(new byte[]{1, 2}).hashCode(), row(new byte[]{1, 2}).hashCode());
        assertNotEquals(row(new byte[]{1, 2}), row(new byte[]{3}));
        assertFalse(row(new byte[]{1}).equals(null));
        assertFalse(row(new byte[]{1}).equals("not a row"));
    }

    @Test
    void equalsComparesEveryScalarComponent() {
        OutboxPublisher.MlsOutboxRow base = row(new byte[]{1});

        assertNotEquals(base, new OutboxPublisher.MlsOutboxRow(UUID.randomUUID(), CONVERSATION_ID, MESSAGE_ID,
                "mls.message", SENDER_ID, 7, "application", new byte[]{1}, CREATED_AT));
        assertNotEquals(base, new OutboxPublisher.MlsOutboxRow(EVENT_ID, CONVERSATION_ID, MESSAGE_ID,
                "mls.message", SENDER_ID, 8, "application", new byte[]{1}, CREATED_AT));
        assertNotEquals(base, new OutboxPublisher.MlsOutboxRow(EVENT_ID, CONVERSATION_ID, MESSAGE_ID,
                "mls.message", SENDER_ID, 7, "proposal", new byte[]{1}, CREATED_AT));
        assertNotEquals(base, new OutboxPublisher.MlsOutboxRow(EVENT_ID, CONVERSATION_ID, MESSAGE_ID,
                "mls.message", SENDER_ID, 7, "application", new byte[]{1}, CREATED_AT.plusSeconds(1)));
    }

    @Test
    void toStringShowsIdentityWithoutCiphertext() {
        String rendered = row(new byte[]{1, 2, 3}).toString();

        assertTrue(rendered.contains(EVENT_ID.toString()));
        assertTrue(rendered.contains("epoch=7"));
        assertFalse(rendered.contains("[1, 2, 3]"));
    }
}
