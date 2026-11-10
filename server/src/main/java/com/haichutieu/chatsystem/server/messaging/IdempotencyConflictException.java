package com.haichutieu.chatsystem.server.messaging;

public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("The idempotency key was already used for a different message.");
    }
}
