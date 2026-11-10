package com.haichutieu.chatsystem.desktop.shared.messaging;

import com.haichutieu.chatsystem.desktop.shared.SessionApi;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Receives server events and reconnects with bounded exponential backoff. */
public final class RealtimeConnection implements AutoCloseable {
    private static final int MAX_EVENT_CHARS = 1_100_000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final SessionApi session;
    private final Consumer<String> eventHandler;
    private final Runnable afterConnect;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chat-websocket-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile boolean closed;
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> pendingReconnect;

    public RealtimeConnection(SessionApi session, Consumer<String> eventHandler, Runnable afterConnect) {
        this.session = session;
        this.eventHandler = eventHandler;
        this.afterConnect = afterConnect;
    }

    public void start() {
        if (closed) throw new IllegalStateException("Realtime connection is closed.");
        if (!started.compareAndSet(false, true)) return;
        connect();
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> pending = pendingReconnect;
        if (pending != null) pending.cancel(false);
        WebSocket active = socket;
        if (active != null) active.sendClose(WebSocket.NORMAL_CLOSURE, "Client closed");
        reconnectExecutor.shutdownNow();
    }

    private void connect() {
        if (closed) return;
        try {
            session.webSocketBuilder().buildAsync(session.webSocketUri(), new Listener())
                    .whenComplete((connected, failure) -> {
                        if (failure != null) scheduleReconnect();
                        else socket = connected;
                    });
        } catch (RuntimeException exception) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closed || !reconnectScheduled.compareAndSet(false, true)) return;
        int attempt = Math.min(attempts.getAndIncrement(), 7);
        long ceiling = Math.min(250L << attempt, MAX_BACKOFF_MILLIS);
        long delay = ThreadLocalRandom.current().nextLong(Math.max(1, ceiling / 2), ceiling + 1);
        pendingReconnect = reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (closed) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closed");
                return;
            }
            socket = webSocket;
            attempts.set(0);
            reconnectScheduled.set(false);
            try {
                afterConnect.run();
            } catch (RuntimeException exception) {
                webSocket.abort();
                scheduleReconnect();
                return;
            }
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > MAX_EVENT_CHARS) {
                text.setLength(0);
                webSocket.sendClose(1009, "Event exceeds size limit");
                return CompletableFuture.completedFuture(null);
            }
            if (last) {
                String event = text.toString();
                text.setLength(0);
                try {
                    eventHandler.accept(event);
                } catch (RuntimeException exception) {
                    webSocket.abort();
                    scheduleReconnect();
                    return CompletableFuture.completedFuture(null);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.sendClose(1003, "Binary events are unsupported");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            scheduleReconnect();
        }
    }
}
