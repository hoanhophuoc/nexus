package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat timeline's contract while it changes underneath the reader: a message the shell did not
 * ask for never moves the viewport, a reader who is already at the newest row keeps following it, and
 * a revealed search hit is scrolled to by its own position rather than by its index.
 *
 * <p>The timeline is a virtualized list, so "is the message there" is asked of the item model and
 * the viewport questions are asked of the list's own scroll bar.
 */
class ChatTimelineScrollFxTest {
    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String PASSWORD = "correct horse battery staple";
    private static final int HISTORY = 40;
    /** Long enough that a narrower window really re-wraps the rows and grows the timeline. */
    private static final String SEARCHED = "history line 7 with enough text to wrap across a "
            + "narrower window and keep wrapping";

    @Test
    void keepsTheReaderInPlaceAndFollowsOnlyAtTheNewestRow(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            // The connect callback repaints and then writes the status; waiting for the status keeps
            // that repaint out of the measurement below.
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));

            scrollToTop(root);
            assertThat(position(root)).as("the test starts at the oldest message").isLessThan(0.05);

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "late arrival", "2026-11-09T12:00:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));
            assertThat(position(root))
                    .as("an arriving message must not pull a reader out of the history")
                    .isLessThan(0.05);
            assertThat(hasMessage(root, "late arrival"))
                    .as("the arrival is in the timeline even though it is off screen").isTrue();

            scrollToBottom(root);
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "newest arrival", "2026-11-09T13:00:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 3, Duration.ofSeconds(10));
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            assertThat(atBottom(root)).as("a reader at the newest row keeps following it")
                    .isTrue();

            // A backfilled message reorders the timeline, so it is rebuilt rather than appended; the
            // reader must still be left at the newest row they were watching.
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "older arrival", "2026-11-01T09:00:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 4, Duration.ofSeconds(10));
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            assertThat(atBottom(root)).as("a rebuild after a backfill does not jump")
                    .isTrue();
        }
    }

    /**
     * Scrolling up has to end the follow intent, or the next resize pulls the reader back to the
     * newest row: narrowing the window re-wraps the rows, which changes the content height and
     * triggers the same follow a new message would.
     */
    @Test
    void stopsFollowingWhenTheReaderScrollsUpAndTheWindowResizes(@TempDir Path home)
            throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             Shell shell = openShell(home, server, null)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));

            // The shell opened on the newest row, so it is following the tail here.
            scrollToBottom(root);
            // A wheel scroll is what clears the follow intent.
            scrollUp(root);
            setPosition(root, 0.3);
            int visibleBefore = onFx(() -> visibleCount(root));
            onFx(() -> {
                shell.stage().setWidth(760);
                root.applyCss();
                root.layout();
                return true;
            });
            // The test is only meaningful once the narrow window really re-wrapped the rows.
            waitForFx(() -> visibleCount(root) != visibleBefore, Duration.ofSeconds(5));
            assertThat(position(root))
                    .as("a resize must not snap a reader who scrolled away from the newest row")
                    .isLessThan(0.5);

            // Back at the newest row the shell follows again, which a send makes explicit.
            onFx(() -> {
                shell.stage().setWidth(1180);
                root.applyCss();
                root.layout();
                return true;
            });
            scrollToBottom(root);
            int messagesBeforeSend = onFx(() -> messages(root));
            onFx(() -> {
                ((TextField) root.lookup("#composeField")).setText("sent from the test");
                ((Button) root.lookup("#sendButton")).fire();
                return true;
            });
            // Waiting for the queued row to disappear is what says the send's own repaint has landed;
            // that repaint sets the follow intent again, so measuring before it arrives would read a
            // stale state.
            waitForFx(() -> messages(root) > messagesBeforeSend && pending(root) == 0,
                    Duration.ofSeconds(10));

            // The keyboard moves the viewport without a ScrollEvent, so it needs its own path.
            pressKey(root, KeyCode.PAGE_UP);
            pressKey(root, KeyCode.HOME);
            setPosition(root, 0.3);
            int visibleBeforeKeys = onFx(() -> visibleCount(root));
            onFx(() -> {
                shell.stage().setWidth(760);
                root.applyCss();
                root.layout();
                return true;
            });
            waitForFx(() -> visibleCount(root) != visibleBeforeKeys, Duration.ofSeconds(5));
            assertThat(position(root))
                    .as("a resize must not snap a reader who scrolled up with the keyboard")
                    .isLessThan(0.5);
        }
    }

    /**
     * A realtime event that reorders the history forces a rebuild instead of an append. A reader in
     * the middle of the history must be left on the same message, not moved to the top or bottom.
     */
    @Test
    void keepsTheReadersPlaceWhenAReorderedTimelineIsRebuilt(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));

            setPosition(root, 0.45);
            Thread.sleep(200);
            String anchorBefore = onFx(() -> firstVisibleText(root));
            assertThat(anchorBefore).as("the test starts on a message in the middle").isNotBlank();

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "backfilled line", "2026-11-01T08:00:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));

            waitForFx(() -> firstVisibleText(root).equals(anchorBefore), Duration.ofSeconds(5));
            assertThat(onFx(() -> firstVisibleText(root)))
                    .as("a rebuild keeps the message the reader was looking at")
                    .isEqualTo(anchorBefore);
            assertThat(position(root)).as("a rebuild in the middle does not jump to the newest row")
                    .isLessThan(0.9);
        }
    }

    @Test
    void revealsASearchHitWhereItActuallySits(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             Shell shell = openShell(home, server, null)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));

            TextField search = (TextField) root.lookup("#searchField");
            assertThat(search).as("the shell has a search field").isNotNull();
            onFx(() -> {
                search.setText("history line 7 with");
                return true;
            });
            ListView<?> results = (ListView<?>) root.lookup("#searchResults");
            waitForFx(() -> results != null && results.getItems().size() == 1,
                    Duration.ofSeconds(10));
            onFx(() -> {
                results.getSelectionModel().selectFirst();
                return true;
            });
            waitForFx(() -> root.lookup(".message-highlight") != null, Duration.ofSeconds(10));
            Node highlight = root.lookup(".message-highlight");
            assertThat(textOf(highlight)).as("the hit is the message that was searched for")
                    .contains("history line 7 with");
            waitForFx(() -> insideViewport(root, root.lookup(".message-highlight")),
                    Duration.ofSeconds(5));
            assertThat(onFx(() -> insideViewport(root, root.lookup(".message-highlight"))))
                    .as("the revealed hit sits inside the viewport").isTrue();
        }
    }

    /**
     * One mounted shell with the plumbing every case above shares. Closing it also restores the
     * isolated home, so a failing case cannot leak a cache into the next one.
     */
    private record Shell(Stage stage, UserAppContext context, Parent root, String previousHome)
            implements AutoCloseable {
        @Override
        public void close() {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static Shell openShell(Path home, FakeChatServer server, FakeWebSocketServer realtime)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        Stage stage = onFx(Stage::new);
        UserAppContext context = new UserAppContext(stage, server.origin().toString(),
                realtime != null, realtime == null ? null : realtime.uri(), theme, i18n);
        context.activate(context.signIn("alice", PASSWORD.toCharArray()));
        seedHistory(context);
        Parent root = onFx(() -> ViewLoader.load(ViewLoader.SHELL,
                new ShellController(context, () -> { }), i18n));
        Scene scene = new Scene(root, 1180, 760);
        theme.apply(scene);
        onFx(() -> {
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            return true;
        });
        assertThat(root.lookup("#messageList")).as("the shell has a message list").isNotNull();
        return new Shell(stage, context, root, previousHome);
    }

    /**
     * History with increasing timestamps and slow enough growth to overflow the viewport; the fake
     * server's own message keeps an earlier date, so the order of everything is fixed.
     */
    private static void seedHistory(UserAppContext context) throws Exception {
        for (int index = 0; index < HISTORY; index++) {
            String content = index == 7 ? SEARCHED
                    : "history line " + index + " with enough text to wrap across a narrower "
                            + "window and keep wrapping";
            context.cache().upsert(new CachedMessage(UUID.randomUUID(),
                    FakeChatServer.CONVERSATION_ID, FakeChatServer.FRIEND_ID, "Bob", content,
                    Instant.parse("2026-11-09T10:00:00Z").plusSeconds(60L * index)));
        }
    }

    private static String messageCreated(UUID id, UUID sender, String content, String createdAt) {
        return "{\"type\":\"MessageCreated\",\"conversationId\":\""
                + FakeChatServer.CONVERSATION_ID + "\",\"message\":{\"id\":\"" + id
                + "\",\"conversationId\":\"" + FakeChatServer.CONVERSATION_ID
                + "\",\"senderId\":\"" + sender + "\",\"content\":\"" + content
                + "\",\"createdAt\":\"" + createdAt + "\"}}";
    }

    // ------------------------------------------------------------------ the list model

    private static ListView<?> timeline(Parent root) {
        return (ListView<?>) root.lookup("#messageList");
    }

    private static int messages(Parent root) {
        ListView<?> list = timeline(root);
        return list == null ? 0 : (int) list.getItems().stream()
                .filter(ShellController.TimelineItem.Bubble.class::isInstance).count();
    }

    private static int pending(Parent root) {
        ListView<?> list = timeline(root);
        return list == null ? 0 : (int) list.getItems().stream()
                .filter(ShellController.TimelineItem.Pending.class::isInstance).count();
    }

    private static boolean hasMessage(Parent root, String content) {
        ListView<?> list = timeline(root);
        return list != null && list.getItems().stream()
                .filter(ShellController.TimelineItem.Bubble.class::isInstance)
                .map(ShellController.TimelineItem.Bubble.class::cast)
                .anyMatch(bubble -> bubble.message().content().contains(content));
    }

    // ------------------------------------------------------------------ the viewport

    /** The index of the first row whose cell is on screen, read from the rendered cells. */
    private static int firstVisibleIndex(Parent root) {
        int first = -1;
        for (Node node : root.lookupAll(".timeline-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) continue;
            Bounds bounds = timeline(root).sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (bounds.getMaxY() <= 0) continue;
            first = first < 0 ? cell.getIndex() : Math.min(first, cell.getIndex());
        }
        return Math.max(first, 0);
    }

    /** The last row whose cell is on screen. */
    private static int lastVisibleIndex(Parent root) {
        int last = -1;
        for (Node node : root.lookupAll(".timeline-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) continue;
            Bounds bounds = timeline(root).sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (bounds.getMinY() >= timeline(root).getHeight()) continue;
            last = Math.max(last, cell.getIndex());
        }
        return last;
    }

    /** True when the newest row is the last one inside the viewport, however tall the list is. */
    private static boolean atBottom(Parent root) {
        ListView<?> list = timeline(root);
        if (list == null || list.getItems().isEmpty()) return true;
        // A recycled cell keeps stale bounds, so the scroll position, not a cell's place, is what
        // says whether the newest row is on screen.
        for (Node node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                if (!bar.isVisible() || bar.getMax() <= 0) return true;
                return bar.getValue() >= bar.getMax() - 1e-3;
            }
        }
        return true;
    }

    /** How far down the history the viewport sits, 0 at the oldest row and 1 at the newest. */
    private static double position(Parent root) {
        ListView<?> list = timeline(root);
        int last = list.getItems().size() - 1;
        if (last <= 0) return 1;
        return Math.min(1.0, (double) firstVisibleIndex(root) / last);
    }

    /** How many rows fit the viewport; a narrower window fits fewer once the text re-wraps. */
    private static int visibleCount(Parent root) {
        int count = 0;
        for (Node node : root.lookupAll(".timeline-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) count++;
        }
        return count;
    }

    private static void setPosition(Parent root, double fraction) {
        onFx(() -> {
            ListView<?> list = timeline(root);
            int last = list.getItems().size() - 1;
            list.scrollTo((int) Math.round(Math.max(0, last) * fraction));
            return true;
        });
        onFx(() -> {
            timeline(root).layout();
            return true;
        });
    }

    private static void scrollToTop(Parent root) {
        onFx(() -> {
            timeline(root).scrollTo(0);
            return true;
        });
        // The cells carry the bounds this reads only after a layout pass, and a list that has just
        // been moved needs that pass to place them; waiting for the toolkit to schedule one leaves
        // the read racing the pulse. Driving it here is the same rule the rest of the harness
        // follows: ask for the pass, do not hope for it.
        waitForFx(() -> {
            ListView<?> list = timeline(root);
            list.applyCss();
            list.layout();
            return firstVisibleIndex(root) == 0;
        }, Duration.ofSeconds(5));
    }

    /**
     * A true bottom: the newest row fully inside the viewport. {@code scrollTo} alone can leave the
     * last row clipped by a pixel or two, which the shell deliberately does not count as "at the
     * bottom", so the skin's own scroll bar is driven to its end here, where the test is allowed to
     * treat it as an input rather than as the value under test.
     */
    private static void scrollToBottom(Parent root) throws InterruptedException {
        // The list measures a row on its layout pass, so a scroll asks for the end from the extent
        // it knew; it has to be repeated across a few pulses to actually land there.
        for (int attempt = 0; attempt < 10; attempt++) {
            onFx(() -> {
                ListView<?> list = timeline(root);
                if (!list.getItems().isEmpty()) list.scrollTo(list.getItems().size() - 1);
                list.layout();
                return true;
            });
            if (atBottom(root)) return;
            Thread.sleep(50);
        }
        waitForFx(() -> atBottom(root), Duration.ofSeconds(2));
    }

    /** A page or home key over the timeline; the guard reads the key, not the resulting value. */
    private static void pressKey(Parent root, KeyCode code) {
        onFx(() -> {
            Event.fireEvent(timeline(root), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                    false, false, false, false));
            return true;
        });
    }

    /** A wheel scroll event over the timeline; the guard reads the event, not the value. */
    private static void scrollUp(Parent root) {
        onFx(() -> {
            Event.fireEvent(timeline(root), new ScrollEvent(ScrollEvent.SCROLL,
                    10, 10, 10, 10, false, false, false, false,
                    false, false, 0, 40, 0, 40,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return true;
        });
    }

    /** The text of the message cell nearest the top of the viewport, top to bottom. */
    private static String firstVisibleText(Parent root) {
        for (Node cell : root.lookupAll(".timeline-cell")) {
            if (!(cell instanceof ListCell<?> listCell) || listCell.isEmpty()) continue;
            Bounds inList = timeline(root).sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
            if (inList.getMaxY() <= 0) continue;
            String text = textOf(cell);
            if (!text.isBlank()) return text.trim();
        }
        return "";
    }

    private static boolean labelsContain(Parent root, String text) {
        return root.lookupAll(".label").stream()
                .filter(javafx.scene.control.Label.class::isInstance)
                .map(javafx.scene.control.Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static String textOf(Node node) {
        if (node == null) return "";
        return node.lookupAll(".label").stream()
                .filter(javafx.scene.control.Label.class::isInstance)
                .map(javafx.scene.control.Label.class::cast)
                .map(javafx.scene.control.Label::getText)
                .reduce("", (left, right) -> left + " " + right);
    }

    private static boolean insideViewport(Parent root, Node node) {
        ListView<?> list = timeline(root);
        if (list == null || node == null) return false;
        Bounds inList = list.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        return inList.getMinY() >= -1 && inList.getMaxY() <= list.getHeight() + 1;
    }
}
