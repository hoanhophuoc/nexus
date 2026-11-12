package com.nexus.desktop.shared.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiComponentsInitialsTest {
    @Test
    void usesTheFirstLettersOfTheFirstAndLastWord() {
        assertEquals("JD", UiComponents.initials("John Doe"));
        assertEquals("JD", UiComponents.initials("  John   Middle   Doe  "));
        assertEquals("A", UiComponents.initials("Ada"));
    }

    @Test
    void fallsBackForEmptyOrMissingNames() {
        assertEquals("?", UiComponents.initials(null));
        assertEquals("?", UiComponents.initials(""));
        assertEquals("?", UiComponents.initials("   "));
    }

    @Test
    void upperCasesTheInitials() {
        assertEquals("LN", UiComponents.initials("linh nguyen"));
    }
}
