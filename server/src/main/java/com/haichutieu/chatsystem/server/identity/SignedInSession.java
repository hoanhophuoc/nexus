package com.haichutieu.chatsystem.server.identity;

public record SignedInSession(UserProfile profile, String token) {
}
