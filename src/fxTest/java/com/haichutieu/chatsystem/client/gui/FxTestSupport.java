package com.haichutieu.chatsystem.client.gui;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class FxTestSupport {

    private static boolean started;

    private FxTestSupport() {
    }

    public static synchronized void startToolkit() {
        if (started) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the JavaFX toolkit", e);
        }
        Platform.setImplicitExit(false);
        started = true;
    }

    public static <T> T call(Callable<T> action) {
        startToolkit();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("JavaFX action failed", e);
        }
    }

    public static void run(ThrowingRunnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
