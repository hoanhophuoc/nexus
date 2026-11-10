package com.haichutieu.chatsystem.server.identity;

import java.util.UUID;

public record SessionIdentity(UUID userId, String role) {
}
