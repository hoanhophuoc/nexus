package com.nexus.desktop.user;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal RFC 6455 endpoint used to push realtime events to the desktop client. */
final class FakeWebSocketServer implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket listener;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile Socket client;
    private volatile OutputStream output;
    private volatile boolean closed;

    FakeWebSocketServer() throws IOException {
        listener = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        Thread.ofVirtual().name("fake-websocket").start(this::accept);
    }

    URI uri() {
        return URI.create("ws://127.0.0.1:" + listener.getLocalPort() + "/ws/v1");
    }

    void awaitClient(TimeUnit unit, long timeout) throws InterruptedException {
        if (!connected.await(timeout, unit)) {
            throw new AssertionError("The desktop client did not open the realtime socket.");
        }
    }

    synchronized void sendText(String text) throws IOException {
        OutputStream stream = output;
        if (stream == null) throw new IOException("No WebSocket client is connected.");
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        stream.write(0x81);
        if (payload.length < 126) {
            stream.write(payload.length);
        } else if (payload.length <= 0xffff) {
            stream.write(126);
            stream.write((payload.length >>> 8) & 0xff);
            stream.write(payload.length & 0xff);
        } else {
            stream.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) stream.write((payload.length >>> shift) & 0xff);
        }
        stream.write(payload);
        stream.flush();
    }

    private void accept() {
        // One connection at a time, but not one connection in total: after a client drops, the
        // listener stays open so a reconnect is answered by a fresh handshake. Without the loop a
        // reconnecting client's second connection sat in the backlog unanswered, which made a
        // reconnect impossible to exercise.
        while (!closed) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException listenerClosed) {
                return;
            }
            client = socket;
            try {
                InputStream input = socket.getInputStream();
                String headers = readHeaders(input);
                String key = header(headers, "Sec-WebSocket-Key");
                if (key == null) {
                    socket.close();
                    continue;
                }
                OutputStream stream = socket.getOutputStream();
                stream.write(("HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                stream.flush();
                synchronized (this) {
                    output = stream;
                }
                connected.countDown();
                consumeFrames(input);
            } catch (IOException exception) {
                if (closed) return;
                // A client that drops mid-handshake leaves the listener open for the next attempt.
            }
        }
    }

    /**
     * Drops the connected client the way a network failure does, keeping the listener open. The
     * client's reconnect then reaches the same endpoint instead of a listener that is gone.
     */
    synchronized void closeClient() {
        Socket active = client;
        client = null;
        output = null;
        if (active == null) return;
        try {
            active.close();
        } catch (IOException ignored) {
            // Dropping the test's own client is best effort.
        }
    }

    private void consumeFrames(InputStream input) throws IOException {
        while (!closed) {
            int first = input.read();
            if (first < 0) return;
            int second = input.read();
            if (second < 0) return;
            int opcode = first & 0x0f;
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) input.read() << 8) | input.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | input.read();
            }
            boolean masked = (second & 0x80) != 0;
            byte[] mask = new byte[4];
            if (masked) {
                if (input.readNBytes(mask, 0, 4) != 4) return;
            }
            byte[] payload = input.readNBytes((int) Math.min(length, 1_100_000));
            if (opcode == 8) return;
            if (opcode == 9) {
                sendPong(payload);
            }
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            }
        }
    }

    private synchronized void sendPong(byte[] payload) throws IOException {
        OutputStream stream = output;
        if (stream == null) return;
        stream.write(0x8a);
        stream.write(Math.min(payload.length, 125));
        stream.write(payload, 0, Math.min(payload.length, 125));
        stream.flush();
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = input.read();
            if (next < 0) break;
            bytes.write(next);
            matched = switch (matched) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : 0;
                default -> matched;
            };
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static String header(String headers, String name) {
        Matcher matcher = Pattern.compile("(?im)^" + Pattern.quote(name) + ":\\s*(.+?)\\s*$")
                .matcher(headers);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String acceptKey(String key) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IOException("Could not calculate the WebSocket accept key.", exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (client != null) client.close();
        } catch (IOException ignored) {
            // Closing the test server is best effort.
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // Closing the test server is best effort.
        }
    }
}
