package com.nexus.desktop.shared.testing;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The helper exists to explain a blocked JavaFX thread, and that explanation is the part nothing else
 * reaches: every other suite gets work back inside the budget. These block the thread from another
 * thread on purpose and check the failure names the frame it is stuck in, including through
 * {@code waitForFx}, which must report at its own deadline rather than sit out the task budget.
 */
class FxThreadTest {

    @Test
    void namesTheFrameTheJavaFxThreadIsStuckIn() throws Exception {
        CountDownLatch release = blockTheFxThread();
        try {
            assertThatThrownBy(() -> FxThread.onFx(() -> "never runs"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("did not run this task within " + FxThread.BUDGET.toSeconds() + "s")
                    .hasMessageContaining("so it is blocked, not slow")
                    .hasMessageContaining("JavaFX thread stack")
                    .hasMessageContaining("holdTheFxThread");
        } finally {
            release.countDown();
        }

        assertThat(FxThread.onFx(() -> "after the block"))
                .as("the thread is usable once it is released").isEqualTo("after the block");
    }

    @Test
    void waitForFxTimesOutInsteadOfHangingWhenTheThreadIsBlocked() throws Exception {
        CountDownLatch release = blockTheFxThread();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> FxThread.waitForFx(() -> false, Duration.ofMillis(200)))
                    .as("the caller's deadline wins over the helper's task budget")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Timed out waiting for the JavaFX UI to update.")
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        } finally {
            release.countDown();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("it reports its own timeout instead of waiting out the 10 second task budget")
                .isLessThan(Duration.ofSeconds(3));

        assertThat(FxThread.onFx(() -> "after the block"))
                .as("the thread is usable once it is released").isEqualTo("after the block");
    }

    /** Makes the FX thread busy and returns the latch that frees it, ready for the test's finally. */
    private static CountDownLatch blockTheFxThread() throws InterruptedException {
        FxThread.onFx(() -> null); // the toolkit has to be up before it can be blocked
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        new Thread(() -> Platform.runLater(() -> holdTheFxThread(blocked, release))).start();
        assertThat(blocked.await(10, TimeUnit.SECONDS)).as("the FX thread is now busy").isTrue();
        return release;
    }

    /** Blocks the FX thread until the test releases it, and leaves its own name in the stack dump. */
    private static void holdTheFxThread(CountDownLatch blocked, CountDownLatch release) {
        blocked.countDown();
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
