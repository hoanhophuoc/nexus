package com.nexus.server.messaging;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeliveryTest {
    private static final UUID EVENT_ID = UUID.fromString("6f1d1f6a-4c4b-4a3f-9a1e-1b2c3d4e5f60");

    private static OutboxPublisher.Delivery delivery(byte[] payload) {
        return new OutboxPublisher.Delivery(EVENT_ID, payload);
    }

    @Test
    void equalsComparesThePayloadByContent() {
        assertEquals(delivery(new byte[]{1, 2}), delivery(new byte[]{1, 2}));
        assertEquals(delivery(new byte[]{1, 2}).hashCode(), delivery(new byte[]{1, 2}).hashCode());
        assertNotEquals(delivery(new byte[]{1, 2}), delivery(new byte[]{1, 3}));
    }

    @Test
    void equalsComparesTheEventId() {
        assertNotEquals(delivery(new byte[]{1}),
                new OutboxPublisher.Delivery(UUID.randomUUID(), new byte[]{1}));
        assertFalse(delivery(new byte[]{1}).equals(null));
        assertFalse(delivery(new byte[]{1}).equals("not a delivery"));
    }

    @Test
    void toStringNamesTheEventAndLeavesThePayloadOut() {
        assertEquals("Delivery[" + EVENT_ID + "]", delivery(new byte[]{9, 8}).toString());
    }
}
