package com.nexus.server.social;

public final class SocialConflictException extends RuntimeException {
    public SocialConflictException() {
        super("The relationship cannot be changed in its current state.");
    }
}
