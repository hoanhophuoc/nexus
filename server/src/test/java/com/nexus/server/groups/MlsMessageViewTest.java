package com.nexus.server.groups;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlsMessageViewTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void equalsComparesCiphertextByContent() {
        MlsMessageView left = new MlsMessageView(ID, 3, "application", new byte[]{1, 2, 3}, CREATED_AT);
        MlsMessageView same = new MlsMessageView(ID, 3, "application", new byte[]{1, 2, 3}, CREATED_AT);
        MlsMessageView otherCiphertext = new MlsMessageView(ID, 3, "application", new byte[]{9}, CREATED_AT);

        assertEquals(left, same);
        assertEquals(left.hashCode(), same.hashCode());
        assertNotEquals(left, otherCiphertext);
        assertFalse(left.equals(null));
        assertFalse(left.equals("not a view"));
    }

    @Test
    void equalsComparesEveryScalarComponent() {
        MlsMessageView base = new MlsMessageView(ID, 3, "application", new byte[]{1}, CREATED_AT);

        assertNotEquals(base, new MlsMessageView(UUID.randomUUID(), 3, "application", new byte[]{1}, CREATED_AT));
        assertNotEquals(base, new MlsMessageView(ID, 4, "application", new byte[]{1}, CREATED_AT));
        assertNotEquals(base, new MlsMessageView(ID, 3, "proposal", new byte[]{1}, CREATED_AT));
        assertNotEquals(base, new MlsMessageView(ID, 3, "application", new byte[]{1}, CREATED_AT.plusSeconds(1)));
    }

    @Test
    void toStringDoesNotPrintCiphertext() {
        MlsMessageView view = new MlsMessageView(ID, 3, "application", new byte[]{1, 2, 3}, CREATED_AT);
        String rendered = view.toString();

        assertTrue(rendered.contains(ID.toString()));
        assertTrue(rendered.contains("epoch=3"));
        assertFalse(rendered.contains("[1, 2, 3]"));
    }
}
