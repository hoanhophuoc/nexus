package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
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
            ScrollPane scroll = shell.scroll();
            waitForFx(() -> bubbles(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            // The connect callback repaints and then writes the status; waiting for the status keeps
            // that repaint out of the measurement below.
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));

            onFx(() -> {
                scroll.setVvalue(0);
                root.applyCss();
                root.layout();
                return true;
            });
            Thread.sleep(200);
            assertThat(onFx(scroll::getVvalue)).as("the test starts at the oldest message")
                    .isLessThan(0.05);

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "late arrival", "2026-11-09T12:00:00Z"));
            waitForFx(() -> bubbles(root) == HISTORY + 2, Duration.ofSeconds(10));
            assertThat(onFx(scroll::getVvalue))
                    .as("an arriving message must not pull a reader out of the history")
                    .isLessThan(0.05);
            assertThat(onFx(() -> labelsContain(root, "late arrival")))
                    .as("the arrival is rendered even though it is off screen").isTrue();

            onFx(() -> {
                scroll.setVvalue(1.0);
                root.applyCss();
                root.layout();
                return true;
            });
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "newest arrival", "2026-11-09T13:00:00Z"));
            waitForFx(() -> bubbles(root) == HISTORY + 3, Duration.ofSeconds(10));
            assertThat(onFx(scroll::getVvalue))
                    .as("a reader at the newest row keeps following it").isGreaterThan(0.99);

            // A backfilled message reorders the timeline, so it is rebuilt rather than appended; the
            // reader must still be left at the newest row they were watching.
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "older arrival", "2026-11-01T09:00:00Z"));
            waitForFx(() -> bubbles(root) == HISTORY + 4, Duration.ofSeconds(10));
            assertThat(onFx(scroll::getVvalue))
                    .as("a rebuild after a backfill does not jump").isGreaterThan(0.99);
        }
    }

    /**
     * Scrolling up has to end the follow intent, or the next resize pulls the reader back to the
     * newest row: narrowing the window re-wraps the rows, which grows the content and triggers the
     * same height change that keeps a follower at the bottom.
     */
    @Test
    void stopsFollowingWhenTheReaderScrollsUpAndTheWindowResizes(@TempDir Path home)
            throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             Shell shell = openShell(home, server, null)) {
            Parent root = shell.root();
            ScrollPane scroll = shell.scroll();
            waitForFx(() -> bubbles(root) >= HISTORY + 1, Duration.ofSeconds(10));
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));

            // The shell opened on the newest row, so it is following the tail here.
            onFx(() -> {
                scroll.setVvalue(1.0);
                root.applyCss();
                root.layout();
                return true;
            });
            // A wheel scroll is what clears the follow intent; the value below stands in for the
            // position the skin moves to in response.
            scrollUp(scroll);
            onFx(() -> {
                scroll.setVvalue(0.3);
                return true;
            });
            double heightBefore = onFx(() -> rowsHeight(root));
            onFx(() -> {
                shell.stage().setWidth(760);
                root.applyCss();
                root.layout();
                return true;
            });
            // The test is only meaningful once the narrow window really made the rows taller.
            waitForFx(() -> rowsHeight(root) > heightBefore + 1, Duration.ofSeconds(5));
            assertThat(onFx(scroll::getVvalue))
                    .as("a resize must not snap a reader who scrolled away from the newest row")
                    .isLessThan(0.5);

            // Back at the newest row the shell follows again, which a send makes explicit.
            onFx(() -> {
                shell.stage().setWidth(1180);
                scroll.setVvalue(1.0);
                root.applyCss();
                root.layout();
                return true;
            });
            int bubblesBeforeSend = onFx(() -> bubbles(root));
            onFx(() -> {
                ((TextField) root.lookup("#composeField")).setText("sent from the test");
                ((Button) root.lookup("#sendButton")).fire();
                return true;
            });
            // Waiting for the queued row to disappear is what says the send's own repaint has landed;
            // that repaint sets the follow intent again, so measuring before it arrives would read a
            // stale state.
            waitForFx(() -> bubbles(root) > bubblesBeforeSend && pendingBubbles(root) == 0,
                    Duration.ofSeconds(10));

            // The keyboard moves the viewport without a ScrollEvent, so it needs its own path.
            pressKey(scroll, KeyCode.PAGE_UP);
            pressKey(scroll, KeyCode.HOME);
            onFx(() -> {
                scroll.setVvalue(0.3);
                return true;
            });
            double heightBeforeKeys = onFx(() -> rowsHeight(root));
            onFx(() -> {
                shell.stage().setWidth(760);
                root.applyCss();
                root.layout();
                return true;
            });
            waitForFx(() -> rowsHeight(root) > heightBeforeKeys + 1, Duration.ofSeconds(5));
            assertThat(onFx(scroll::getVvalue))
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
            ScrollPane scroll = shell.scroll();
            waitForFx(() -> bubbles(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));

            onFx(() -> {
                scroll.setVvalue(0.45);
                root.applyCss();
                root.layout();
                return true;
            });
            Thread.sleep(200);
            String anchorBefore = onFx(() -> firstVisibleText(scroll, root));
            assertThat(anchorBefore).as("the test starts on a message in the middle").isNotBlank();

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "backfilled line", "2026-11-01T08:00:00Z"));
            waitForFx(() -> bubbles(root) == HISTORY + 2, Duration.ofSeconds(10));

            assertThat(onFx(() -> firstVisibleText(scroll, root)))
                    .as("a rebuild keeps the message the reader was looking at")
                    .isEqualTo(anchorBefore);
            assertThat(onFx(scroll::getVvalue))
                    .as("a rebuild in the middle does not jump to the newest row")
                    .isLessThan(0.9);
        }
    }

    @Test
    void revealsASearchHitWhereItActuallySits(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             Shell shell = openShell(home, server, null)) {
            Parent root = shell.root();
            ScrollPane scroll = shell.scroll();
            waitForFx(() -> bubbles(root) >= HISTORY + 1, Duration.ofSeconds(10));

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
            waitForFx(() -> insideViewport(scroll, root.lookup(".message-highlight")),
                    Duration.ofSeconds(5));
            assertThat(onFx(() -> insideViewport(scroll, root.lookup(".message-highlight"))))
                    .as("the revealed hit sits inside the viewport").isTrue();
        }
    }

    /**
     * One mounted shell with the plumbing every case above shares. Closing it also restores the
     * isolated home, so a failing case cannot leak a cache into the next one.
     */
    private record Shell(Stage stage, UserAppContext context, Parent root, ScrollPane scroll,
                         String previousHome) implements AutoCloseable {
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
        ScrollPane scroll = (ScrollPane) root.lookup("#messageScroll");
        assertThat(scroll).as("the shell has a message scroll pane").isNotNull();
        return new Shell(stage, context, root, scroll, previousHome);
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

    /** A page or home key over the timeline; the guard reads the key, not the resulting value. */
    private static void pressKey(ScrollPane scroll, KeyCode code) {
        onFx(() -> {
            Event.fireEvent(scroll, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                    false, false, false, false));
            return true;
        });
    }

    /** A wheel scroll event over the timeline; the guard reads the event, not the value. */
    private static void scrollUp(ScrollPane scroll) {
        onFx(() -> {
            Event.fireEvent(scroll, new ScrollEvent(ScrollEvent.SCROLL,
                    10, 10, 10, 10, false, false, false, false,
                    false, false, 0, 40, 0, 40,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return true;
        });
    }

    private static double rowsHeight(Parent root) {
        Node rows = root.lookup("#messageRows");
        return rows instanceof VBox box ? box.getHeight() : 0;
    }

    /** The text of the first message row that overlaps the viewport, top to bottom. */
    private static String firstVisibleText(ScrollPane scroll, Parent root) {
        Bounds viewport = scroll.getViewportBounds();
        if (viewport.getHeight() <= 0) return "";
        for (Node row : root.lookupAll(".message-row")) {
            Bounds inScroll = scroll.sceneToLocal(row.localToScene(row.getBoundsInLocal()));
            if (inScroll.getMaxY() <= 0 || inScroll.getMinY() >= viewport.getHeight()) continue;
            String text = textOf(row).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static int bubbles(Parent root) {
        return root.lookupAll(".message-bubble").size();
    }

    private static int pendingBubbles(Parent root) {
        return (int) root.lookupAll(".message-bubble").stream()
                .filter(node -> node.getStyleClass().contains("pending")).count();
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

    private static boolean insideViewport(ScrollPane scroll, Node node) {
        if (scroll == null || node == null) return false;
        Bounds viewport = scroll.getViewportBounds();
        if (viewport.getHeight() <= 0) return false;
        Bounds inScroll = scroll.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        return inScroll.getMinY() >= -1 && inScroll.getMaxY() <= viewport.getHeight() + 1;
    }
}
