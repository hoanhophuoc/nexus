package com.nexus.desktop.shared.testing;

import javafx.application.Platform;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Runs test work on the shared JavaFX thread, and says what that thread is doing when it cannot.
 * The budget is deliberately short: a task that runs longer is not slow, it is blocked — by network
 * or file I/O, or by waiting for another FX task — and a 30 second timeout only hides which. The
 * first task of a suite loads FXML and CSS, which measures under four seconds on a loaded machine,
 * so anything below the budget here is ordinary work.
 */
public final class FxThread {
    static final Duration BUDGET = Duration.ofSeconds(10);

    private FxThread() {
    }

    public static <T> T onFx(Callable<T> action) {
        return call(action, BUDGET);
    }

    private static <T> T call(Callable<T> action, Duration budget) {
        start();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return await(result, budget);
    }

    /**
     * Polls on the FX thread until {@code condition} holds; later JavaFX work needs the wait. Each
     * poll is bounded by the time left, so a blocked thread fails at the caller's deadline instead of
     * spending the whole task budget on its own, and the dump it carries stays attached as the cause.
     */
    public static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                if (Boolean.TRUE.equals(call(condition::getAsBoolean, Duration.ofNanos(remaining)))) return;
            } catch (FxThreadBlocked blocked) {
                throw new AssertionError("Timed out waiting for the JavaFX UI to update.", blocked);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the JavaFX UI.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the JavaFX UI to update.");
    }

    /** A blocked thread, as its own type so {@code waitForFx} can report it as its own timeout. */
    private static final class FxThreadBlocked extends AssertionError {
        FxThreadBlocked(String message, TimeoutException cause) {
            super(message, cause);
        }
    }

    private static <T> T await(CompletableFuture<T> result, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (true) {
            try {
                return result.get(25, TimeUnit.MILLISECONDS);
            } catch (TimeoutException busy) {
                if (System.nanoTime() >= deadline) throw blocked(busy, budget);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the JavaFX thread.", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new AssertionError(cause);
            }
        }
    }

    /** Names the culprit: the frames the FX thread is sitting in when the budget ran out. */
    private static AssertionError blocked(TimeoutException busy, Duration budget) {
        String waited = budget.toMillis() >= 1000 ? budget.toSeconds() + "s" : budget.toMillis() + "ms";
        StringBuilder report = new StringBuilder("The JavaFX thread did not run this task within ")
                .append(waited)
                .append(", so it is blocked, not slow. No network or file I/O belongs in an FX task.\n")
                .append("JavaFX thread stack:");
        for (StackTraceElement frame : fxThread().getStackTrace()) {
            report.append("\n  at ").append(frame);
        }
        return new FxThreadBlocked(report.toString(), busy);
    }

    private static Thread fxThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("JavaFX Application Thread".equals(thread.getName())) return thread;
        }
        return Thread.currentThread();
    }

    private static synchronized void start() {
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
