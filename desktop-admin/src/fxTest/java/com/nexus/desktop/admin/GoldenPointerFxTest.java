package com.nexus.desktop.admin;

import com.nexus.desktop.shared.testing.GoldenPointer;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.util.concurrent.atomic.AtomicReference;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden harness parks the pointer so a hover highlight cannot reach a baseline, which moves the
 * cursor on a developer's own display. These tests hold the harness to both halves of that bargain,
 * including on the path where the capture fails — the case where a leaked cursor would be least
 * noticed.
 */
class GoldenPointerFxTest {
    private static final Point DEVELOPER_CURSOR = new Point(300, 200);

    @Test
    void parksThePointerWhileTheCaptureRunsAndPutsItBackAfterwards() throws Exception {
        Parent root = bareScene();
        moveTo(DEVELOPER_CURSOR);
        AtomicReference<Point> during = new AtomicReference<>();

        GoldenPointer.capture(root, () -> during.set(pointerLocation()));

        assertThat(during.get()).as("pointer parked while the capture runs").isEqualTo(new Point(1, 1));
        assertThat(pointerLocation()).as("pointer returned to the developer's position")
                .isEqualTo(DEVELOPER_CURSOR);
    }

    @Test
    void restoresThePointerWhenTheCaptureFails() throws Exception {
        Parent root = bareScene();
        moveTo(DEVELOPER_CURSOR);
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        try {
            GoldenPointer.capture(root, () -> {
                throw new AssertionError("capture rejected for this test");
            });
        } catch (AssertionError expected) {
            failure.set(expected);
        }

        assertThat(failure.get()).as("the capture failure reaches its caller").isNotNull();
        assertThat(pointerLocation()).as("pointer restored even though the capture failed")
                .isEqualTo(DEVELOPER_CURSOR);
    }

    /** A scene with no rows, so these tests observe only the pointer contract. */
    private static Parent bareScene() throws Exception {
        return onFx(() -> {
            Parent bare = new VBox();
            new Scene(bare, 160, 120);
            bare.applyCss();
            bare.layout();
            return bare;
        });
    }

    private static Point pointerLocation() {
        return MouseInfo.getPointerInfo().getLocation();
    }

    private static void moveTo(Point position) throws Exception {
        new Robot().mouseMove(position.x, position.y);
        // Let the X server deliver the move before the capture records where it started.
        Thread.sleep(100);
    }
}
