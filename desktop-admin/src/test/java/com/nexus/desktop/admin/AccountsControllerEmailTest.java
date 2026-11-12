package com.nexus.desktop.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsControllerEmailTest {
    @Test
    void acceptsAWellFormedAddress() {
        assertTrue(AccountsController.looksLikeEmail("user@example.test"));
        assertTrue(AccountsController.looksLikeEmail("first.last+tag@mail.example.co.uk"));
    }

    @Test
    void rejectsAddressesWithTheWrongShape() {
        assertFalse(AccountsController.looksLikeEmail(null));
        assertFalse(AccountsController.looksLikeEmail(""));
        assertFalse(AccountsController.looksLikeEmail("plain"));
        assertFalse(AccountsController.looksLikeEmail("@example.test"));
        assertFalse(AccountsController.looksLikeEmail("user@@example.test"));
        assertFalse(AccountsController.looksLikeEmail("user@example"));
        assertFalse(AccountsController.looksLikeEmail("user@example."));
        assertFalse(AccountsController.looksLikeEmail("user@.test"));
        assertFalse(AccountsController.looksLikeEmail("user name@example.test"));
        assertFalse(AccountsController.looksLikeEmail("user@example.test "));
    }
}
