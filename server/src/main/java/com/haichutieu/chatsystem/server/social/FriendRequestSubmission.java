package com.haichutieu.chatsystem.server.social;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FriendRequestSubmission(@NotNull UUID userId) {
}
