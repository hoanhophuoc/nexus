package com.nexus.server.identity;

import java.util.UUID;

public record SessionIdentity(UUID userId, String role) {
}
