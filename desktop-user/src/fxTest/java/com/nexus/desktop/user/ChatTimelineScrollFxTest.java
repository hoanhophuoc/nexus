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
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
    /** Several wrapped lines tall, so it is only partially visible when it is the top row. */
    private static final String LONG = "long wrapped line 21 with a body that spans several lines "
            + "of the viewport so it can be clipped by the top edge and still be the message the "
            + "reader was reading when the timeline is rebuilt underneath them";
    private static final String LONG_MARKER = "long wrapped line 21";

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

            settleTimeline(root);
            // The reader leaves the newest row the way a person does — with the keyboard — so the
            // shell's follow intent ends with the move. A programmatic scroll does not end it: the
            // shell is still following, and a later width change (the vertical bar appearing is one)
            // re-applies the follow and jumps a reader who is reading the history. That is what a
            // hosted runner measured: the viewport at 31/43 of the history when this assertion ran.
            pressKey(root, KeyCode.HOME);
            waitForFx(() -> {
                ListView<?> list = timeline(root);
                list.applyCss();
                list.layout();
                return firstVisibleIndex(root) == 0;
            }, Duration.ofSeconds(5));
            assertThat(position(root)).as("the test starts at the oldest message").isLessThan(0.05);

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "late arrival", "2026-11-09T12:00:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));
            // The arrival changes the item count on the pulse that also rebuilds the rows, so the
            // viewport is only measurable once the list has placed them and anything the shell
            // started moving has stopped. Measuring the count straight away is what made this
            // assertion depend on how fast the runner's pulses came.
            settleTimeline(root);
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
            settleTimeline(root);
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

            settleTimeline(root);
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

    /**
     * A message that lands while the shell is still re-applying its move to the newest row must not
     * cancel that move. The scroll bar read mid-move says the reader left the bottom; the follow the
     * shell itself started says they did not, and the reader keeps seeing what arrives.
     */
    @Test
    void anArrivalDuringTheMoveToTheNewestRowKeepsFollowing(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            settleTimeline(root);

            // Several rounds, each sent back to back without waiting for the previous one: a round
            // lands while the rebuild the last arrival caused is still re-applying the move to the
            // newest row. Once a move is cancelled the shell stops following for good, so the later
            // rounds are the ones that catch it rather than the luck of a single interleaving.
            for (int round = 0; round < 5; round++) {
                final int roundIndex = round;
                int before = onFx(() -> messages(root));
                for (int index = 0; index < 4; index++) {
                    realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                            "arrival " + roundIndex + "." + index + " during the move",
                            String.format("2026-11-09T%02d:%02d:00Z", 14 + roundIndex, index * 10)));
                }
                waitForFx(() -> messages(root) == before + 4, Duration.ofSeconds(20));

                // The shell re-applies its move on the pulses that measure the rows it is moving to,
                // and a two-core runner can stretch the wait between them; the budget below is about
                // the machine, not about how far the move is allowed to travel.
                waitForFx(() -> atBottom(root), Duration.ofSeconds(30));
                assertThat(onFx(() -> atBottom(root)))
                        .as("round %d: a follow that is still running survives an arrival", roundIndex)
                        .isTrue();
                assertThat(onFx(() -> labelsContain(root, "arrival " + roundIndex + ".3 during the move")))
                        .as("round %d: the newest arrival is on screen", roundIndex).isTrue();
            }
        }
    }

    /**
     * A reader who drags the scrollbar thumb away from the newest row has left the tail, and an
     * arrival must not pull them back; returning to the newest row resumes the follow.
     *
     * <p>The drag is a real one — press, move and release on the bar's own thumb node — so the
     * skin's input handling decides the new position. Writing a value straight onto the bar would
     * skip exactly the code a user exercises.
     */
    @Test
    void draggingTheScrollbarThumbAwayFromTheTailStopsFollowing(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));
            settleTimeline(root);
            scrollToBottom(root);

            double before = onFx(() -> scrollBarValue(root));
            dragThumbUp(root);
            waitForFx(() -> scrollBarValue(root) < before - 1e-3, Duration.ofSeconds(5));
            assertThat(onFx(() -> atBottom(root)))
                    .as("dragging the thumb up moved the viewport off the newest row").isFalse();

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "after the thumb drag", "2026-11-09T12:30:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));
            assertThat(onFx(() -> atBottom(root)))
                    .as("an arrival must not follow the tail after a scrollbar drag").isFalse();

            scrollToBottom(root);
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "after returning to the tail", "2026-11-09T13:30:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 3, Duration.ofSeconds(10));
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            assertThat(onFx(() -> atBottom(root)))
                    .as("a reader back at the newest row follows it again").isTrue();
        }
    }

    /**
     * The same rule for the scrollbar's track: pressing it away from the thumb moves the viewport by
     * a page, and the arrival that follows must not snap the reader to the newest row. The press and
     * release go to the track node, which is what a real click on the bar lands on.
     */
    @Test
    void clickingTheScrollbarTrackAwayFromTheTailStopsFollowing(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));
            settleTimeline(root);
            scrollToBottom(root);

            double before = onFx(() -> scrollBarValue(root));
            clickTrackAboveTheThumb(root);
            waitForFx(() -> scrollBarValue(root) < before - 1e-3, Duration.ofSeconds(5));
            assertThat(onFx(() -> atBottom(root)))
                    .as("a track click moved the viewport off the newest row").isFalse();

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "after the track click", "2026-11-09T12:45:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));
            assertThat(onFx(() -> atBottom(root)))
                    .as("an arrival must not follow the tail after a track click").isFalse();

            scrollToBottom(root);
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "after the track click returned", "2026-11-09T13:45:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 3, Duration.ofSeconds(10));
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            assertThat(onFx(() -> atBottom(root)))
                    .as("a reader back at the newest row follows it again").isTrue();
        }
    }

    /**
     * A press inside a message cell is not a scroll. The timeline's guard reads the press's ancestry,
     * so clicking a message's action menu leaves the follow intent alone and the arrival that follows
     * still lands at the newest row; only the list's own scroll bar ends the follow.
     *
     * <p>The press is a real one dispatched at the button's node, not {@code MenuButton.fire()}: the
     * filter under test sees the pointer's press, not the action a release would raise, and the press
     * is what used to end the follow for every descendant of the list.
     */
    @Test
    void pressingAMessageActionButtonKeepsFollowingTheNewestRow(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));
            settleTimeline(root);
            scrollToBottom(root);
            assertThat(onFx(() -> atBottom(root)))
                    .as("the test starts at the newest row, where the shell follows").isTrue();

            pressMessageAction(root);
            // The press must not move the viewport by itself either: it is a click on content.
            assertThat(onFx(() -> atBottom(root)))
                    .as("clicking a message action does not scroll the timeline").isTrue();

            // The intent is observable where a re-wrap re-applies it. This move is the harness's own
            // programmatic scroll, which the gesture tests deliberately avoid because the shell does
            // not read it as the reader leaving the tail; narrowing re-wraps every row, so a shell
            // that still follows returns to the newest row and one whose intent the press ended
            // stays in the history.
            setPosition(root, 0.45);
            onFx(() -> {
                shell.stage().setWidth(760);
                root.applyCss();
                root.layout();
                return true;
            });
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            assertThat(onFx(() -> atBottom(root)))
                    .as("a press on a message action must not end the follow intent")
                    .isTrue();

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "after the action press", "2026-11-09T12:50:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));
            waitForFx(() -> atBottom(root), Duration.ofSeconds(5));
            assertThat(onFx(() -> atBottom(root)))
                    .as("a press on a message action must not end the follow intent")
                    .isTrue();
            assertThat(onFx(() -> labelsContain(root, "after the action press")))
                    .as("the arrival is on screen, at the newest row").isTrue();
        }
    }

    /**
     * The anchor the shell restores after a rebuild is a message id, not a pixel offset: a
     * {@code ListView} addresses rows with {@code scrollTo(index)} and the internal {@code VirtualFlow}
     * that owns the pixels is off limits. So a rebuilt timeline puts the anchored row at the top of the
     * viewport, which keeps the reader on the same message but drops the slice of a tall row that had
     * scrolled above the edge.
     *
     * <p>This test states that trade-off instead of assuming it: a long wrapped row is left partially
     * clipped by the top edge, a backfilled message forces a rebuild, and the row is still the top row
     * afterwards — now flush with the edge.
     */
    @Test
    void keepsATallAnchoredRowVisibleWhenTheTimelineIsRebuilt(@TempDir Path home) throws Exception {
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer();
             Shell shell = openShell(home, server, realtime)) {
            Parent root = shell.root();
            waitForFx(() -> messages(root) >= HISTORY + 1, Duration.ofSeconds(10));
            realtime.awaitClient(TimeUnit.SECONDS, 10);
            waitForFx(() -> labelsContain(root, "Up to date"), Duration.ofSeconds(10));
            settleTimeline(root);

            // The tall row the test anchors on is a real arrival that lands in the middle of the
            // history, so the shared fixture stays exactly what the other cases measure.
            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID, LONG,
                    "2026-11-09T10:21:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 2, Duration.ofSeconds(10));
            settleTimeline(root);

            int index = messageIndex(root, LONG_MARKER);
            assertThat(index).as("the long row the test anchors on").isGreaterThan(0);
            onFx(() -> {
                timeline(root).scrollTo(index);
                return true;
            });
            waitForFx(() -> firstVisibleIndex(root) == index, Duration.ofSeconds(5));

            // A short thumb drag down the bar clips the tall row's own text by a few pixels above the
            // edge; the row stays the topmost visible one because it is several lines tall.
            for (int attempt = 0; attempt < 20 && onFx(() -> topOfCellContaining(root, LONG_MARKER)) >= -1;
                 attempt++) {
                dragThumb(root, 6);
                Thread.sleep(30);
            }
            assertThat(onFx(() -> topOfCellContaining(root, LONG_MARKER)))
                    .as("the row is only partially visible before the rebuild").isLessThan(-1);
            String anchorBefore = onFx(() -> firstVisibleText(root));
            assertThat(anchorBefore).contains(LONG_MARKER);

            realtime.sendText(messageCreated(UUID.randomUUID(), FakeChatServer.FRIEND_ID,
                    "backfilled under the reader", "2026-11-01T07:00:00Z"));
            waitForFx(() -> messages(root) == HISTORY + 3, Duration.ofSeconds(10));
            waitForFx(() -> firstVisibleText(root).contains(LONG_MARKER), Duration.ofSeconds(5));
            assertThat(onFx(() -> firstVisibleText(root)))
                    .as("the same message is still the row at the top after the rebuild")
                    .isEqualTo(anchorBefore);
            assertThat(onFx(() -> topOfCellContaining(root, LONG_MARKER)))
                    .as("the row is put back flush with the top: the exact pixel offset is not kept")
                    .isGreaterThanOrEqualTo(-1);
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

    /**
     * Waits for the shell's own move to the newest row to stop changing the viewport. The shell lands
     * on the newest row after mounting and re-applies that move until the list has measured its rows;
     * a test that sets a position mid-move is moved back, so the harness lets the move finish first
     * instead of measuring against a viewport something else is still steering. A sample also drives
     * the layout pass: cells carry the bounds the viewport questions read only once they are placed,
     * and an arrival changes the item count on the same pulse that rebuilds them.
     */
    private static void settleTimeline(Parent root) throws InterruptedException {
        double previousValue = Double.NaN;
        int previousIndex = -1;
        for (int attempt = 0; attempt < 30; attempt++) {
            double[] sample = onFx(() -> {
                ListView<?> list = timeline(root);
                if (list == null) return new double[] {-1, -1};
                list.applyCss();
                list.layout();
                return new double[] {scrollBarValue(root), firstVisibleIndex(root)};
            });
            if (!Double.isNaN(previousValue) && Math.abs(sample[0] - previousValue) < 1e-6
                    && (int) sample[1] == previousIndex) {
                return;
            }
            previousValue = sample[0];
            previousIndex = (int) sample[1];
            Thread.sleep(200);
        }
    }

    private static double scrollBarValue(Parent root) {
        ListView<?> list = timeline(root);
        if (list == null) return -1;
        for (Node node : list.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                return bar.getValue();
            }
        }
        return -1;
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
     * bottom", so the move is repeated until the list's own scroll bar reports its end.
     *
     * <p>Each attempt drives the layout pass and reads the bar straight after it. The earlier
     * version retried ten times and then left the rest to a two-second wait for a pulse; on a loaded
     * runner that delivered no pulse inside the wait, the test failed although the scroll was one
     * frame away. Reading in the attempt itself keeps the wait bounded by the FX thread's own
     * budget, so a stall delays the landing instead of failing it.
     */
    private static void scrollToBottom(Parent root) throws InterruptedException {
        // The list measures a row on its layout pass, so a scroll asks for the end from the extent
        // it knew; it has to be repeated until that extent has grown to the whole history.
        for (int attempt = 0; attempt < 40; attempt++) {
            boolean atEnd = onFx(() -> {
                ListView<?> list = timeline(root);
                if (!list.getItems().isEmpty()) list.scrollTo(list.getItems().size() - 1);
                list.layout();
                return atBottom(root);
            });
            if (atEnd) return;
            Thread.sleep(25);
        }
        assertThat(onFx(() -> atBottom(root)))
                .as("the timeline did not reach its newest row; the scroll bar is at %s of its "
                        + "maximum", onFx(() -> scrollBarValue(root)))
                .isTrue();
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

    /** The index of the bubble whose content contains {@code marker}, or -1. */
    private static int messageIndex(Parent root, String marker) {
        ListView<?> list = timeline(root);
        for (int index = 0; index < list.getItems().size(); index++) {
            if (list.getItems().get(index) instanceof ShellController.TimelineItem.Bubble bubble
                    && bubble.message().content().contains(marker)) {
                return index;
            }
        }
        return -1;
    }

    /** How far below the list's top the row carrying {@code marker} starts; negative is clipped. */
    private static double topOfCellContaining(Parent root, String marker) {
        ListView<?> list = timeline(root);
        for (Node cell : list.lookupAll(".timeline-cell")) {
            if (!(cell instanceof ListCell<?> listCell) || listCell.isEmpty()) continue;
            if (!textOf(cell).contains(marker)) continue;
            Bounds inList = list.sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
            return inList.getMinY();
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** The timeline's own vertical scroll bar: the node a reader's drag and track clicks land on. */
    private static ScrollBar verticalScrollBar(Parent root) {
        for (Node node : timeline(root).lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) return bar;
        }
        throw new AssertionError("The timeline has no vertical scroll bar");
    }

    /**
     * Presses the bar's thumb, drags it toward the oldest row and releases it. The events go to the
     * skin's thumb node and bubble to the bar's own input handling, which is what a real drag does.
     */
    private static void dragThumbUp(Parent root) {
        onFx(() -> {
            ScrollBar bar = verticalScrollBar(root);
            Node thumb = bar.lookup(".thumb");
            assertThat(thumb).as("the scroll bar draws a thumb").isNotNull();
            // Far enough to leave the tail by several rows, short enough to stay on the track.
            dragThumbOnFx(thumb, -Math.min(thumb.getBoundsInLocal().getHeight(), 120));
            return true;
        });
    }

    /** A real thumb drag of {@code travel} pixels; positive moves toward the newest row. */
    private static void dragThumb(Parent root, double travel) {
        onFx(() -> {
            Node thumb = verticalScrollBar(root).lookup(".thumb");
            assertThat(thumb).as("the scroll bar draws a thumb").isNotNull();
            dragThumbOnFx(thumb, travel);
            return true;
        });
    }

    private static void dragThumbOnFx(Node thumb, double travel) {
        Bounds thumbBounds = thumb.getBoundsInLocal();
        double x = thumbBounds.getMinX() + thumbBounds.getWidth() / 2;
        double start = thumbBounds.getMinY() + thumbBounds.getHeight() / 2;
        fireMouse(thumb, MouseEvent.MOUSE_PRESSED, x, start);
        for (int step = 1; step <= 5; step++) {
            fireMouse(thumb, MouseEvent.MOUSE_DRAGGED, x, start + travel * step / 5.0);
        }
        fireMouse(thumb, MouseEvent.MOUSE_RELEASED, x, start + travel);
    }

    /** Presses and releases the bar's track above the thumb, which is a page toward the oldest row. */
    private static void clickTrackAboveTheThumb(Parent root) {
        onFx(() -> {
            ScrollBar bar = verticalScrollBar(root);
            Node track = bar.lookup(".track");
            Node thumb = bar.lookup(".thumb");
            assertThat(track).as("the scroll bar draws a track").isNotNull();
            Bounds trackBounds = track.getBoundsInLocal();
            Bounds thumbInTrack = track.sceneToLocal(thumb.localToScene(thumb.getBoundsInLocal()));
            double x = trackBounds.getMinX() + trackBounds.getWidth() / 2;
            double y = Math.max(trackBounds.getMinY() + 1, thumbInTrack.getMinY() - 4);
            fireMouse(track, MouseEvent.MOUSE_PRESSED, x, y);
            fireMouse(track, MouseEvent.MOUSE_RELEASED, x, y);
            return true;
        });
    }

    /**
     * A real press on a message's action button, dispatched at the button so it travels through the
     * timeline's filters exactly as a pointer press on that descendant does.
     */
    private static void pressMessageAction(Parent root) {
        onFx(() -> {
            MenuButton action = null;
            for (Node cell : timeline(root).lookupAll(".timeline-cell")) {
                if (!(cell instanceof ListCell<?> listCell) || listCell.isEmpty()) continue;
                if (cell.lookup(".icon-button") instanceof MenuButton menu) {
                    action = menu;
                    break;
                }
            }
            assertThat(action).as("a message in the timeline offers its action menu").isNotNull();
            Bounds bounds = action.getBoundsInLocal();
            fireMouse(action, MouseEvent.MOUSE_PRESSED,
                    bounds.getWidth() / 2, bounds.getHeight() / 2);
            return true;
        });
    }

    /** One primary-button mouse event at a node-local point, as the skin's behaviour reads them. */
    private static void fireMouse(Node target, javafx.event.EventType<MouseEvent> type,
                                  double x, double y) {
        Event.fireEvent(target, new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                type != MouseEvent.MOUSE_RELEASED, false, false,
                false, false, true, null));
    }

    /**
     * The text of the message cell nearest the top of the viewport.
     *
     * <p>{@code lookupAll} hands its cells back in no particular order, so "the first visible cell"
     * in that set is an arbitrary row and two reads of the same viewport can disagree. The topmost
     * row is the one with the lowest index.
     */
    private static String firstVisibleText(Parent root) {
        ListCell<?> top = null;
        for (Node cell : root.lookupAll(".timeline-cell")) {
            if (!(cell instanceof ListCell<?> listCell) || listCell.isEmpty()) continue;
            Bounds inList = timeline(root).sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
            if (inList.getMaxY() <= 0) continue;
            if (top == null || listCell.getIndex() < top.getIndex()) top = listCell;
        }
        String text = top == null ? "" : textOf(top);
        return text.isBlank() ? "" : text.trim();
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
