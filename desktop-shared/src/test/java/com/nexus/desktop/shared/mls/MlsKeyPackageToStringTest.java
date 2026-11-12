package com.nexus.desktop.shared.mls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlsKeyPackageToStringTest {
    @Test
    void toStringNamesTheOwnerAndExpiryWithoutKeyMaterial() throws Exception {
        MlsIdentity identity = MlsIdentity.generate(java.util.UUID.randomUUID(), "device-under-test");
        MlsKeyPackage keyPackage = MlsKeyPackage.create(identity);

        String rendered = keyPackage.toString();

        assertTrue(rendered.contains(identity.userId().toString()));
        assertTrue(rendered.contains("device=device-under-test"));
        assertTrue(rendered.contains("expires="));
        assertFalse(rendered.contains(java.util.HexFormat.of().formatHex(keyPackage.identityKey())));
    }
}
