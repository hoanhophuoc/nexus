package com.haichutieu.chatsystem.server.identity;

public final class AccountConflictException extends RuntimeException {
    public AccountConflictException() {
        super("An account with those details already exists.");
    }
}
