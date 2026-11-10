package com.haichutieu.chatsystem.server.identity;

public interface PasswordResetNotifier {
    void send(String email, String rawToken, String tokenHash);
}
