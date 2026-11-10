package com.haichutieu.chatsystem.util;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class Util {
    // Jackson 3 writes dates as ISO-8601 by default; keep the numeric timestamp
    // format used by the client/server protocol.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public static <T> String serializeObject(T data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

    // Generic function to deserialize JSON to any type
    public static <T> T deserializeObject(String data, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(data, typeReference);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readLine(StringBuilder sb) {
        int index = sb.indexOf("\n");
        if (index != -1) {
            String line = sb.substring(0, index);
            sb.delete(0, index + 1); // Remove the line and the newline character
            return line;
        }
        return null;
    }

    /**
     * Reads a newline-delimited line from raw bytes. Decoding only after a full
     * line has arrived prevents a UTF-8 character from being split across reads.
     */
    public static String readLine(ByteArrayOutputStream buffer) {
        byte[] bytes = buffer.toByteArray();
        int index = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return null;
        }

        int lineLength = index > 0 && bytes[index - 1] == '\r' ? index - 1 : index;
        String line = new String(bytes, 0, lineLength, StandardCharsets.UTF_8);
        buffer.reset();
        buffer.write(bytes, index + 1, bytes.length - index - 1);
        return line;
    }
}
