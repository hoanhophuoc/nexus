package com.nexus.server.identity;

public record LoginAccount(UserProfile profile, String passwordHash, boolean locked) {
}
