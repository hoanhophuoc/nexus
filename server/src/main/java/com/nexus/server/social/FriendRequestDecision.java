package com.nexus.server.social;

import jakarta.validation.constraints.NotNull;

public record FriendRequestDecision(@NotNull Decision decision) {
    public enum Decision { accept, decline }
}
