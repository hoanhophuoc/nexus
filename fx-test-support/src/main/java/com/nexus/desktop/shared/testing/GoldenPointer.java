package com.nexus.desktop.shared.testing;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TableView;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.time.Duration;
import java.util.List;

import static com.nexus.desktop.shared.testing.FxThread.onFx;

/**
 * Makes the state a golden capture records deterministic, then puts the pointer back.
 *
 * <p>Three pieces of scene state are decided by the environment rather than by Nexus, and each has
 * already made a reviewed baseline unreproducible on another machine:
 * <ul>
 *   <li>the pointer rests wherever the display centre falls, and {@code .list-cell:hover} /
 *       {@code .table-row-cell:hover} paint the row under it with {@code -color-surface-muted};</li>
 *   <li>a table that takes focus selects its focused row, so which row is highlighted depends on
 *       whether the window manager gives the table focus at all;</li>
 *   <li>a control that owns focus draws the {@code :focused} ring, which the capture would record.</li>
 * </ul>
 *
 * <p>So every capture first pins selection and focus, then parks the pointer and refuses to run while
 * a row is hovered or a list/table still owns focus. Parking moves a developer's own cursor when the
 * suite runs against a real display, so the pointer is returned to where it was on every exit,
 * including a failed or refused capture.
 */
public final class GoldenPointer {
    private static final int PARK_X = 1;
    private static final int PARK_Y = 1;
    private static final List<String> HOVERABLE_ROWS = List.of(".list-cell", ".table-row-cell");
    private static final List<String> SELECTABLE = List.of(".list-view", ".table-view");

    private GoldenPointer() {
    }

    /** Pins the scene state, runs one capture, and restores the pointer afterwards. */
    public static void capture(Parent root, Capture capture) throws Exception {
        pin(root);
        Robot robot = robot();
        Point origin = pointerLocation();
        try {
            park(robot, root);
            capture.run();
        } finally {
            if (origin != null) robot.mouseMove(origin.x, origin.y);
        }
    }

    /** A golden capture; it may write a file and assert a ratio, so it is allowed to fail. */
    @FunctionalInterface
    public interface Capture {
        void run() throws Exception;
    }

    /**
     * Clears every table's highlight and moves focus to the scene root, so no control draws the focus
     * ring. A table's selection is otherwise decided by whether it happened to take focus while the
     * screen loaded, which differs between machines; the harness re-selects the row its screen is
     * about inside the capture, after this has made the starting point empty.
     *
     * <p>Lists keep the selection their screen set — the open conversation is part of what the screen
     * means to show, and re-selecting it would re-open the conversation and reload its history.
     */
    private static void pin(Parent root) throws Exception {
        onFx(() -> {
            for (String selector : SELECTABLE) {
                for (Node node : root.lookupAll(selector)) {
                    if (node instanceof TableView<?> table) {
                        table.getSelectionModel().clearSelection();
                    }
                }
            }
            root.setFocusTraversable(true);
            root.requestFocus();
            root.applyCss();
            root.layout();
            return true;
        });
    }

    private static Robot robot() {
        try {
            return new Robot();
        } catch (AWTException | SecurityException unsupported) {
            throw new AssertionError("Golden capture needs a pointer to park; no display is available.",
                    unsupported);
        }
    }

    private static Point pointerLocation() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? null : info.getLocation();
    }

    private static void park(Robot robot, Parent root) throws Exception {
        robot.mouseMove(PARK_X, PARK_Y);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(onFx(() -> settled(root)))) {
                onFx(() -> {
                    root.applyCss();
                    root.layout();
                    return true;
                });
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(
                "Golden scene would capture pointer or focus state: " + onFx(() -> describe(root)));
    }

    /** Names the state that refused to settle, so a runner failure explains itself. */
    private static String describe(Parent root) {
        StringBuilder text = new StringBuilder();
        Node owner = root.getScene() == null ? null : root.getScene().getFocusOwner();
        text.append("focusOwner=").append(owner == null ? "none"
                : owner.getClass().getSimpleName() + owner.getStyleClass());
        for (String selector : SELECTABLE) {
            for (Node node : root.lookupAll(selector)) {
                if (node.isFocused()) {
                    text.append(" | focused=").append(selector).append(node.getStyleClass());
                }
            }
        }
        int hovered = 0;
        for (String selector : HOVERABLE_ROWS) {
            for (Node node : root.lookupAll(selector)) {
                if (node.getPseudoClassStates().stream()
                        .anyMatch(pseudo -> "hover".equals(pseudo.getPseudoClassName()))) {
                    hovered++;
                }
            }
        }
        return text.append(" | hoveredRows=").append(hovered).toString();
    }

    /** The scene is only safe to capture with no hovered row and no control holding focus. */
    private static boolean settled(Parent root) {
        return !anyRowHovered(root) && !anyControlFocused(root);
    }

    private static boolean anyRowHovered(Parent root) {
        for (String selector : HOVERABLE_ROWS) {
            for (Node node : root.lookupAll(selector)) {
                if (node.getPseudoClassStates().stream()
                        .anyMatch(pseudo -> "hover".equals(pseudo.getPseudoClassName()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyControlFocused(Parent root) {
        for (String selector : SELECTABLE) {
            for (Node node : root.lookupAll(selector)) {
                if (node.isFocused()) return true;
            }
        }
        return false;
    }
}
