package com.nexus.server.groups;

public class MlsPayloadTooLargeException extends RuntimeException {
    public MlsPayloadTooLargeException(String message) {
        super(message);
    }
}
