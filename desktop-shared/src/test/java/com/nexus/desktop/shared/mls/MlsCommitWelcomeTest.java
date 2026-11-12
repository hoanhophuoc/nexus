package com.nexus.desktop.shared.mls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The welcome carries three byte arrays, so equality has to compare their content. */
class MlsCommitWelcomeTest {
    private static MlsCommit.Welcome welcome(byte fill) {
        byte[] ephemeralKey = new byte[32];
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[48];
        java.util.Arrays.fill(ephemeralKey, fill);
        java.util.Arrays.fill(nonce, fill);
        java.util.Arrays.fill(ciphertext, fill);
        return new MlsCommit.Welcome(ephemeralKey, nonce, ciphertext);
    }

    @Test
    void equalsComparesEveryArrayByContent() {
        assertEquals(welcome((byte) 1), welcome((byte) 1));
        assertEquals(welcome((byte) 1).hashCode(), welcome((byte) 1).hashCode());
        assertNotEquals(welcome((byte) 1), welcome((byte) 2));
        assertFalse(welcome((byte) 1).equals(null));
        assertFalse(welcome((byte) 1).equals("not a welcome"));
    }

    @Test
    void toStringReportsSizesInsteadOfKeyMaterial() {
        String rendered = welcome((byte) 7).toString();

        assertTrue(rendered.contains("32/12/48"));
        assertFalse(rendered.contains("7"));
    }
}
