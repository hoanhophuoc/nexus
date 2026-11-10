package com.haichutieu.chatsystem.server.social;

import jakarta.validation.constraints.NotNull;

public record FriendRequestDecision(@NotNull Decision decision) {
    public enum Decision { accept, decline }
}
