package com.nexus.server.messaging;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConversationRequest(@NotNull UUID participantId) {
}
