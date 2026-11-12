package com.nexus.server.identity;

import java.util.UUID;

public record PasswordResetAccount(UUID userId, String email) {
}
