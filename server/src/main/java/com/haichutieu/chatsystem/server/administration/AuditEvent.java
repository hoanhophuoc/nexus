package com.haichutieu.chatsystem.server.administration;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(UUID id, UUID actorId, String action, UUID targetId, Instant occurredAt) {
}
