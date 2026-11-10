package com.haichutieu.chatsystem.server.identity;

public final class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException() {
        super("The password reset token is invalid or expired.");
    }
}
