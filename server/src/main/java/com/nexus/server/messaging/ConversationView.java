package com.nexus.server.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationView(UUID id, String name, String type, Instant createdAt,
                               List<ConversationMemberView> members) {
}
