package com.haichutieu.chatsystem.server.identity;

import java.util.UUID;

public record PasswordResetAccount(UUID userId, String email) {
}
