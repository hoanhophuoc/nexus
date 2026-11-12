package com.nexus.server.administration;

import java.time.Instant;
import java.util.UUID;

public record LoginHistoryEntry(UUID userId, String username, String fullName, Instant occurredAt) {
}
