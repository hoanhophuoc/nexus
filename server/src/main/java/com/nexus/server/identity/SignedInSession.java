package com.nexus.server.identity;

public record SignedInSession(UserProfile profile, String token) {
}
