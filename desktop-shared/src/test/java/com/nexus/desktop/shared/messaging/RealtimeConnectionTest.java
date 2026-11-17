package com.nexus.desktop.shared.messaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the socket listener directly and the scheduler by hand: the connection's retry timing is
 * arithmetic on an attempt counter, so the suite reads the delay it asks for instead of waiting it out.
 */
class RealtimeConnectionTest {

    @Test
    void startOpensOneSocketAndRunsAfterConnectOnOpen() {
        Fixture fixture = new Fixture();
        fixture.sockets.willOpen(fixture.socket);

        fixture.connection.start();
        fixture.connection.start();

        assertThat(fixture.sockets.attempts).as("a second start is a no-op").isEqualTo(1);
        fixture.sockets.listener(0).onOpen(fixture.socket);
        assertThat(fixture.afterConnect.get()).isEqualTo(1);
        assertThat(fixture.socket.requests).as("the listener asks for the first frame").isEqualTo(1);
    }

    @Test
    void aFailedAttemptRetriesAfterHalfToWholeCeilingThatGrows() {
        Fixture fixture = new Fixture();
        fixture.sockets.willFail();
        fixture.sockets.willFail();
        fixture.sockets.willFail();

        fixture.connection.start();

        assertThat(fixture.scheduler.delay(0)).as("attempt 0 waits inside the 250 ms ceiling")
                .isBetween(125L, 250L);
        fixture.scheduler.run(0);
        assertThat(fixture.scheduler.delay(1)).as("the ceiling doubles with every attempt")
                .isBetween(250L, 500L);
        fixture.scheduler.run(1);
        assertThat(fixture.scheduler.delay(2)).isBetween(500L, 1000L);
        assertThat(fixture.sockets.attempts).isEqualTo(3);
    }

    @Test
    void anOpenResetsTheBackoffSoTheNextDropWaitsTheShortestCeiling() {
        Fixture fixture = new Fixture();
        fixture.sockets.willFail();
        fixture.sockets.willFail();
        fixture.sockets.willOpen(fixture.socket);
        fixture.connection.start();
        fixture.scheduler.run(0);
        fixture.scheduler.run(1);

        fixture.sockets.listener(1).onOpen(fixture.socket);
        fixture.sockets.listener(1).onClose(fixture.socket, WebSocket.NORMAL_CLOSURE, "server restart");

        assertThat(fixture.scheduler.delay(2)).as("a healthy socket resets the attempt counter")
                .isBetween(125L, 250L);
    }

    @Test
    void oneDropSchedulesOneReconnectEvenWhenAnErrorFollowsTheClose() {
        Fixture fixture = new Fixture();
        fixture.sockets.willOpen(fixture.socket);
        fixture.connection.start();
        fixture.sockets.listener(0).onOpen(fixture.socket);

        fixture.sockets.listener(0).onClose(fixture.socket, 1006, "gone");
        fixture.sockets.listener(0).onError(fixture.socket, new IOException("reset"));

        assertThat(fixture.scheduler.scheduled()).as("the pending retry is not scheduled twice").isEqualTo(1);
    }

    @Test
    void anOpenThatArrivesAfterCloseIsRefused() {
        Fixture fixture = new Fixture();
        // The handshake is still in flight when the connection closes, which is the race a shell
        // signing out mid-connect hits.
        fixture.sockets.willOpenLater();
        fixture.connection.start();

        fixture.connection.close();
        fixture.sockets.listener(0).onOpen(fixture.socket);

        assertThat(fixture.afterConnect.get()).isZero();
        assertThat(fixture.socket.closed).containsExactly("1000 Client closed");
    }

    @Test
    void closingCancelsThePendingReconnectAndTheScheduler() {
        Fixture fixture = new Fixture();
        fixture.sockets.willFail();
        fixture.connection.start();

        fixture.connection.close();

        assertThat(fixture.scheduler.pending(0).isCancelled()).isTrue();
        assertThat(fixture.scheduler.shutdown).isTrue();
        fixture.scheduler.runEvenIfCancelled(0);
        assertThat(fixture.sockets.attempts).as("a cancelled retry never connects again").isEqualTo(1);
    }

    @Test
    void startAfterCloseIsRejected() {
        Fixture fixture = new Fixture();
        fixture.connection.close();

        assertThatThrownBy(fixture.connection::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void textIsDeliveredOnceWhenTheFrameEnds() {
        Fixture fixture = new Fixture();
        opened(fixture);
        WebSocket.Listener listener = fixture.sockets.listener(0);

        listener.onText(fixture.socket, "{\"type\":", false);
        assertThat(fixture.events).as("a partial frame is not an event yet").isEmpty();
        listener.onText(fixture.socket, "\"MessageCreated\"}", true);

        assertThat(fixture.events).containsExactly("{\"type\":\"MessageCreated\"}");
        assertThat(fixture.socket.requests).as("every frame asks for the next one").isEqualTo(3);
    }

    @Test
    void anOversizedEventIsDroppedAndTheSocketClosed() {
        Fixture fixture = new Fixture();
        opened(fixture);

        fixture.sockets.listener(0).onText(fixture.socket, "x".repeat(1_100_001), true);

        assertThat(fixture.events).isEmpty();
        assertThat(fixture.socket.closed).containsExactly("1009 Event exceeds size limit");
    }

    @Test
    void aHandlerFailureAbandonsTheSocketAndRetries() {
        Fixture fixture = new Fixture();
        opened(fixture);
        fixture.handler = event -> {
            throw new IllegalStateException("the shell cannot take this event");
        };

        fixture.sockets.listener(0).onText(fixture.socket, "{}", true);

        assertThat(fixture.socket.aborts).isEqualTo(1);
        assertThat(fixture.scheduler.scheduled()).isEqualTo(1);
    }

    @Test
    void anAfterConnectFailureAbandonsTheSocketAndRetries() {
        Fixture fixture = new Fixture();
        fixture.sockets.willOpen(fixture.socket);
        fixture.onConnect = () -> {
            throw new IllegalStateException("the session is not ready");
        };
        fixture.connection.start();

        fixture.sockets.listener(0).onOpen(fixture.socket);

        assertThat(fixture.socket.aborts).isEqualTo(1);
        assertThat(fixture.scheduler.scheduled()).isEqualTo(1);
        assertThat(fixture.socket.requests).as("a socket that could not be prepared takes no frames").isZero();
    }

    @Test
    void binaryFramesAreRefused() {
        Fixture fixture = new Fixture();
        opened(fixture);

        fixture.sockets.listener(0).onBinary(fixture.socket, ByteBuffer.wrap(new byte[]{1, 2}), true);

        assertThat(fixture.socket.closed).containsExactly("1003 Binary events are unsupported");
    }

    @Test
    void aBuilderThatThrowsIsRetriedLikeAFailedAttempt() {
        Fixture fixture = new Fixture();
        fixture.sockets.refuseNext();

        fixture.connection.start();

        assertThat(fixture.scheduler.scheduled()).isEqualTo(1);
        assertThat(fixture.scheduler.delay(0)).isBetween(125L, 250L);
    }

    private static void opened(Fixture fixture) {
        fixture.sockets.willOpen(fixture.socket);
        fixture.connection.start();
        fixture.sockets.listener(0).onOpen(fixture.socket);
    }

    /** One connection wired to a fake socket factory and a scheduler the test advances. */
    private static final class Fixture {
        private final Sockets sockets = new Sockets();
        private final RecordingScheduler scheduler = new RecordingScheduler();
        private final RecordingWebSocket socket = new RecordingWebSocket();
        private final List<String> events = new ArrayList<>();
        private final AtomicInteger afterConnect = new AtomicInteger();
        private java.util.function.Consumer<String> handler = events::add;
        private Runnable onConnect = afterConnect::incrementAndGet;
        private final RealtimeConnection connection =
                new RealtimeConnection(sockets, event -> handler.accept(event), () -> onConnect.run(), scheduler);
    }

    /** Hands out the socket attempts the test queued, and keeps every listener it was given. */
    private static final class Sockets implements RealtimeConnection.SocketFactory {
        private final List<WebSocket.Listener> listeners = new ArrayList<>();
        private final Deque<CompletableFuture<WebSocket>> results = new ArrayDeque<>();
        private int attempts;
        private boolean refuse;

        private void willOpen(WebSocket socket) {
            results.add(CompletableFuture.completedFuture(socket));
        }

        private void willOpenLater() {
            results.add(new CompletableFuture<>());
        }

        private void willFail() {
            results.add(CompletableFuture.failedFuture(new IOException("no route to the server")));
        }

        private void refuseNext() {
            refuse = true;
        }

        @Override
        public CompletableFuture<WebSocket> connect(WebSocket.Listener listener) {
            attempts++;
            listeners.add(listener);
            if (refuse) {
                refuse = false;
                throw new IllegalStateException("the builder refused the attempt");
            }
            return results.poll();
        }

        private WebSocket.Listener listener(int index) {
            return listeners.get(index);
        }
    }

    /** Schedules on the caller's thread: every retry waits until the test runs it. */
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        private final List<Pending> scheduled = new ArrayList<>();
        private boolean shutdown;

        private RecordingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Pending pending = new Pending(command, unit.toMillis(delay));
            scheduled.add(pending);
            return pending;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        private int scheduled() {
            return scheduled.size();
        }

        private long delay(int index) {
            return scheduled.get(index).delayMillis;
        }

        private Pending pending(int index) {
            return scheduled.get(index);
        }

        private void run(int index) {
            scheduled.get(index).run(false);
        }

        private void runEvenIfCancelled(int index) {
            scheduled.get(index).run(true);
        }
    }

    /** A scheduled retry whose delay is readable and whose task the test runs when it likes. */
    private static final class Pending implements ScheduledFuture<Object> {
        private final Runnable task;
        private final long delayMillis;
        private boolean cancelled;
        private boolean done;

        private Pending(Runnable task, long delayMillis) {
            this.task = task;
            this.delayMillis = delayMillis;
        }

        private void run(boolean evenIfCancelled) {
            if (cancelled && !evenIfCancelled) return;
            done = true;
            task.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(delayMillis, other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    /** Records what the connection asked of the socket. */
    private static final class RecordingWebSocket implements WebSocket {
        private final List<String> closed = new ArrayList<>();
        private int requests;
        private int aborts;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closed.add(statusCode + " " + reason);
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requests++;
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
            aborts++;
        }
    }
}
