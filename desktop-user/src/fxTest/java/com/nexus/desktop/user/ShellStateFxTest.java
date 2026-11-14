package com.nexus.desktop.user;

import com.nexus.sdk.model.Conversation;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One shell, driven through the states a user moves between, with the state written as a table so a
 * new case is a row rather than another 80-line test. Every row does one thing and waits for the
 * shell to settle, so a repaint that never happens fails the row that asked for it instead of the
 * teardown. Screenshots are not involved: these assertions are on the labels the shell actually shows.
 *
 * <p>A row's action runs on the test thread, because the two destructive actions open a
 * {@code showAndWait} dialog: the FX thread parks inside it, so the click that answers it has to come
 * from outside while {@link com.nexus.desktop.shared.testing.FxThread#waitForFx} polls through the
 * dialog's own nested event loop.
 */
class ShellStateFxTest {

    /** One state transition: what the user does, and what the shell must be showing once it settles. */
    private record Step(String state, Runnable action, BooleanSupplier settled) { }

    @Test
    void movesBetweenConversationsAndIgnoresAStaleRealtimeEvent(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer()) {
            server.groupExists();
            server.addDirectMessage("from bob");
            server.addDirectMessageFrom(FakeChatServer.USER_ID, "mine to delete");
            server.addDirectMessageFrom(FakeChatServer.USER_ID, "mine to delete for everyone");
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<UserDesktopApplication> applicationReference = new AtomicReference<>();
            onFx(() -> {
                UserDesktopApplication application =
                        new UserDesktopApplication(server.origin().toString(), true, realtime.uri());
                applicationReference.set(application);
                Stage stage = new Stage();
                stageReference.set(stage);
                application.start(stage);
                return true;
            });
            Stage stage = stageReference.get();
            UserDesktopApplication application = applicationReference.get();
            try {
                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Username").setText("alice");
                    field(scene, "Password").setText("correct horse battery staple");
                    button(scene, "Sign in").fire();
                    return true;
                });
                waitForFx(() -> button(stage, "Send") != null, Duration.ofSeconds(10));
                realtime.awaitClient(TimeUnit.SECONDS, 10);
                // The shell selects the first conversation once its own refresh lands; the table starts
                // from that settled state instead of racing the opening one.
                waitForFx(() -> titleIs(stage, FakeChatServer.FRIEND_NAME), Duration.ofSeconds(10));

                List<Step> table = List.of(
                        new Step("open the group",
                                () -> assertThat(onFx(() -> selectConversation(stage, FakeChatServer.GROUP_NAME)))
                                        .isTrue(),
                                () -> titleIs(stage, FakeChatServer.GROUP_NAME)),
                        new Step("switch to the direct chat and send",
                                () -> onFx(() -> {
                                    assertThat(selectConversation(stage, FakeChatServer.FRIEND_NAME)).isTrue();
                                    messageField(stage).setText("hello again");
                                    button(stage, "Send").fire();
                                    return true;
                                }),
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && labelContaining(stage, "hello again")
                                        && labelContaining(stage, "mine to delete")
                                        && !titleIs(stage, FakeChatServer.GROUP_NAME)),
                        new Step("delete a message for this user only",
                                () -> assertThat(onFx(() -> fireMessageMenu(stage, "mine to delete", "for me")))
                                        .isTrue(),
                                () -> !showsExactly(stage, "mine to delete")),
                        new Step("decline delete for everyone at its confirmation",
                                () -> answerConfirmation(() -> assertThat(fireMessageMenu(stage,
                                        "mine to delete for everyone", "everyone")).isTrue(), false),
                                () -> server.deletedScopes().equals(List.of("me"))
                                        && showsExactly(stage, "mine to delete for everyone")
                                        && !labelContaining(stage, "This message was deleted.")),
                        new Step("delete for everyone through its confirmation",
                                () -> confirmThen(() -> assertThat(fireMessageMenu(stage,
                                        "mine to delete for everyone", "everyone")).isTrue()),
                                () -> server.deletedScopes().contains("everyone")
                                        && labelContaining(stage, "This message was deleted.")),
                        new Step("decline clearing the history at its confirmation",
                                () -> answerConfirmation(() -> {
                                    Button clear = button(stage, "Clear history");
                                    assertThat(clear).as("the direct chat offers clear history").isNotNull();
                                    clear.fire();
                                }, false),
                                () -> server.clearedHistories() == 0
                                        && showsExactly(stage, "from bob")
                                        && showsExactly(stage, "hello again")),
                        new Step("clear the history through its confirmation",
                                () -> confirmThen(() -> {
                                    Button clear = button(stage, "Clear history");
                                    assertThat(clear).as("the direct chat offers clear history").isNotNull();
                                    clear.fire();
                                }),
                                () -> server.clearedHistories() == 1
                                        && !showsExactly(stage, "hello again")
                                        && !showsExactly(stage, "from bob")),
                        new Step("a realtime event for the group that is no longer open",
                                () -> sendGroupMessage(realtime),
                                () -> !labelContaining(stage, "late group message")));

                for (Step step : table) {
                    step.action().run();
                    try {
                        waitForFx(step.settled(), Duration.ofSeconds(10));
                    } catch (AssertionError unmet) {
                        throw new AssertionError(step.state() + ": " + unmet.getMessage()
                                + " (" + onFx(() -> diagnostics(stage)) + ")", unmet);
                    }
                }

                assertThat(server.deletedScopes())
                        .as("both delete scopes went through the row menu's own actions")
                        .containsExactly("me", "everyone");
                // The stale event repainted nothing, and the shell is still the one the user had.
                assertThat(onFx(() -> titleOf(stage)))
                        .as("after the stale group event the direct chat is still open")
                        .isEqualTo(FakeChatServer.FRIEND_NAME);
            } finally {
                onFx(() -> {
                    stage.close();
                    application.stop();
                    return true;
                });
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    /** Answers a confirmation the way most rows do: accept it. */
    private static void confirmThen(Runnable open) {
        answerConfirmation(open, true);
    }

    /**
     * Opens a modal confirmation from the test thread and answers it, waiting for the dialog to appear
     * and for it to go away — a click that never lands would otherwise leave the FX thread parked in
     * {@code showAndWait} and fail the next row with a timeout that names nothing.
     */
    private static void answerConfirmation(Runnable open, boolean accept) {
        String id = accept ? "#confirmDialogConfirm" : "#confirmDialogCancel";
        Platform.runLater(open);
        waitForFx(() -> confirmButton(id) != null, Duration.ofSeconds(10));
        Platform.runLater(() -> {
            Node answer = confirmButton(id);
            if (answer instanceof Button button) button.fire();
        });
        waitForFx(() -> confirmButton(id) == null, Duration.ofSeconds(10));
    }

    /** The dialog's own buttons live in a second window, not in the shell's scene. */
    private static Node confirmButton(String id) {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) continue;
            Node button = window.getScene().lookup(id);
            if (button != null) return button;
        }
        return null;
    }

    private static void sendGroupMessage(FakeWebSocketServer realtime) {
        try {
            realtime.sendText("{\"type\":\"MessageCreated\",\"conversationId\":\""
                    + FakeChatServer.GROUP_ID + "\",\"message\":{\"id\":\""
                    + UUID.randomUUID() + "\",\"conversationId\":\""
                    + FakeChatServer.GROUP_ID + "\",\"senderId\":\""
                    + FakeChatServer.FRIEND_ID + "\",\"content\":\"late group message\","
                    + "\"createdAt\":\"2026-11-14T10:00:00Z\"}}");
        } catch (java.io.IOException failure) {
            throw new AssertionError("The test could not send the late realtime event", failure);
        }
    }

    /**
     * Fires the action whose text contains {@code itemText} on the row showing {@code text}: a menu
     * looked up by position would be the conversation list's own menu, which deletes a chat instead.
     */
    private static boolean fireMessageMenu(Stage stage, String text, String itemText) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        for (Node node : scene.getRoot().lookupAll(".menu-button")) {
            if (!(node instanceof MenuButton menu) || menu.getItems().isEmpty()) continue;
            if (!rowShows(menu, text)) continue;
            for (MenuItem item : menu.getItems()) {
                if (item.getText() != null && item.getText().toLowerCase().contains(itemText)) {
                    item.fire();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the message row around {@code menu} shows exactly {@code text}. Exact, because one
     * seeded message's text is a prefix of another's ("mine to delete" / "… for everyone").
     */
    private static boolean rowShows(Node menu, String text) {
        Node row = menu.getParent();
        while (row != null && !row.getStyleClass().contains("message-row")) row = row.getParent();
        return row != null && row.lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> text.equals(label.getText()));
    }

    /** Enough of the shell to tell a repaint that never happened from one that painted the wrong thing. */
    private static String diagnostics(Stage stage) {
        return titleOf(stage) + ", status "
                + (stage.getScene().lookup("#statusLabel") instanceof Label status ? status.getText() : null)
                + ", rows " + rowsOf(stage);
    }

    private static String rowsOf(Stage stage) {
        Node rows = stage.getScene().lookup("#messageRows");
        if (rows == null) return "none";
        return rows.lookupAll(".label").stream().filter(Label.class::isInstance)
                .map(Label.class::cast).map(Label::getText).toList().toString();
    }

    private static boolean titleIs(Stage stage, String title) {
        return title.equals(titleOf(stage));
    }

    private static String titleOf(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return null;
        return scene.getRoot().lookupAll(".chat-title").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .map(Label::getText).filter(text -> text != null)
                .findFirst().orElse(null);
    }

    private static boolean selectConversation(Stage stage, String name) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        for (Node node : scene.getRoot().lookupAll(".list-view")) {
            if (!(node instanceof ListView<?> list)) continue;
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index) instanceof Conversation conversation
                        && name.equals(conversation.getName())) {
                    list.getSelectionModel().clearAndSelect(index);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean showsExactly(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> text.equals(label.getText()));
    }

    private static boolean labelContaining(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static TextField messageField(Stage stage) {
        if (stage.getScene().lookup("#composeField") instanceof TextField box) return box;
        throw new AssertionError("The shell has no message box");
    }

    private static Button button(Stage stage, String text) {
        return stage.getScene() == null ? null : button(stage.getScene(), text);
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst().orElse(null);
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(candidate -> prompt.equals(candidate.getPromptText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
    }
}
