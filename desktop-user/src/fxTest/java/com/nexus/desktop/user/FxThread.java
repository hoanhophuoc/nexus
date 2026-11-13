package com.nexus.desktop.user;

import javafx.application.Platform;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs work on the shared JavaFX thread and waits until the UI has caught up. */
final class FxThread {
    private FxThread() {
    }

    static <T> T onFx(Callable<T> action) {
        startToolkit();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("JavaFX action failed", exception);
        }
    }

    /** Polls on the FX thread until {@code condition} holds; later JavaFX work needs the wait. */
    static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the JavaFX UI.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the JavaFX UI to update.");
    }

    private static synchronized void startToolkit() {
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start within 30 seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }
}
