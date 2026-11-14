package com.nexus.desktop.user;

/**
 * Identifies one open of a conversation, so a completion that arrives after the user has moved on is
 * recognised as late instead of repainting whatever conversation is open by then.
 */
final class OpenGeneration {
    private int current;

    /** Starts a new open and returns the token its completions have to present. */
    int open() {
        return ++current;
    }

    /** The token of the open in progress, for work that starts while one is already open. */
    int current() {
        return current;
    }

    boolean isCurrent(int token) {
        return token == current;
    }
}
