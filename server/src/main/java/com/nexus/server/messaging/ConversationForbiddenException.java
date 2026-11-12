package com.nexus.server.messaging;

public final class ConversationForbiddenException extends RuntimeException {
    public ConversationForbiddenException() {
        super("A direct conversation requires an active friendship and no block in either direction.");
    }
}
