package com.haichutieu.chatsystem.server.identity;

public record LoginAccount(UserProfile profile, String passwordHash, boolean locked) {
}
