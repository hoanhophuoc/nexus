package com.haichutieu.chatsystem.desktop.shared.mls;

/** Signals invalid, stale, replayed or unauthentic MLS-style group state. */
public class MlsException extends Exception {
    public MlsException(String message) {
        super(message);
    }

    public MlsException(String message, Throwable cause) {
        super(message, cause);
    }
}
