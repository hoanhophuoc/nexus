package com.nexus.desktop.shared.testing;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The helper exists to explain a blocked JavaFX thread, and that explanation is the part nothing else
 * reaches: every other suite gets work back inside the budget. This blocks the thread from another
 * thread on purpose and checks the failure names the frame it is stuck in.
 */
class FxThreadTest {

    @Test
    void namesTheFrameTheJavaFxThreadIsStuckIn() throws Exception {
        FxThread.onFx(() -> null); // starts the toolkit and proves it is usable before the test blocks it

        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread blocker = new Thread(() -> Platform.runLater(() -> holdTheFxThread(blocked, release)));
        blocker.start();
        assertThat(blocked.await(10, TimeUnit.SECONDS)).as("the FX thread is now busy").isTrue();

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
