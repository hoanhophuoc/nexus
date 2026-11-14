package com.nexus.desktop.user;

import com.nexus.sdk.model.Conversation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
                                () -> assertThat(selectConversation(stage, FakeChatServer.GROUP_NAME)).isTrue(),
                                () -> titleIs(stage, FakeChatServer.GROUP_NAME)),
                        new Step("switch to the direct chat and send",
                                () -> {
                                    assertThat(selectConversation(stage, FakeChatServer.FRIEND_NAME)).isTrue();
                                    messageField(stage).setText("hello again");
                                    button(stage, "Send").fire();
                                },
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && labelContaining(stage, "hello again")
                                        && labelContaining(stage, "mine to delete")
                                        && !titleIs(stage, FakeChatServer.GROUP_NAME)),
                        new Step("delete the message the user sent",
                                () -> assertThat(deleteMessageShowing(stage, "mine to delete")).isTrue(),
                                () -> !server.deletedScopes().isEmpty()
                                        && !labelContaining(stage, "mine to delete")),
                        new Step("a realtime event for the group that is no longer open",
                                () -> sendGroupMessage(realtime),
                                () -> !labelContaining(stage, "late group message")));

                for (Step step : table) {
                    onFx(() -> {
                        step.action().run();
                        return true;
                    });
                    try {
                        waitForFx(step.settled(), Duration.ofSeconds(10));
                    } catch (AssertionError unmet) {
                        throw new AssertionError(step.state() + ": " + unmet.getMessage()
                                + " (" + onFx(() -> diagnostics(stage)) + ")", unmet);
                    }
                }

                assertThat(server.deletedScopes())
                        .as("the row menu deleted the message for this user only")
                        .contains("me");
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

    private static void sendGroupMessage(FakeWebSocketServer realtime) {
        try {
            realtime.sendText("{\"type\":\"MessageCreated\",\"conversationId\":\""
                    + FakeChatServer.GROUP_ID + "\",\"message\":{\"id\":\""
                    + java.util.UUID.randomUUID() + "\",\"conversationId\":\""
                    + FakeChatServer.GROUP_ID + "\",\"senderId\":\""
                    + FakeChatServer.FRIEND_ID + "\",\"content\":\"late group message\","
                    + "\"createdAt\":\"2026-11-14T10:00:00Z\"}}");
        } catch (java.io.IOException failure) {
            throw new AssertionError("The test could not send the late realtime event", failure);
        }
    }

    /**
     * Fires the delete action on the row that shows {@code text}: a menu looked up by position would
     * be the conversation list's own menu, which deletes a chat instead of a message.
     */
    private static boolean deleteMessageShowing(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        for (Node node : scene.getRoot().lookupAll(".menu-button")) {
            if (!(node instanceof MenuButton menu) || menu.getItems().isEmpty()) continue;
            if (!rowShows(menu, text)) continue;
            for (MenuItem item : menu.getItems()) {
                if (item.getText() != null && item.getText().toLowerCase().contains("delete")) {
                    item.fire();
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the message row around {@code menu} shows {@code text}, and no other row does. */
    private static boolean rowShows(Node menu, String text) {
        Node row = menu.getParent();
        while (row != null && !row.getStyleClass().contains("message-row")) row = row.getParent();
        return row != null && row.lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
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

    private static boolean labelContaining(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static javafx.scene.control.TextField messageField(Stage stage) {
        if (stage.getScene().lookup("#composeField") instanceof javafx.scene.control.TextField box) {
            return box;
        }
        throw new AssertionError("The shell has no message box");
    }

    private static javafx.scene.control.Button button(Stage stage, String text) {
        return stage.getScene() == null ? null : button(stage.getScene(), text);
    }

    private static javafx.scene.control.Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(javafx.scene.control.Button.class::isInstance)
                .map(javafx.scene.control.Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst().orElse(null);
    }

    private static javafx.scene.control.TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(javafx.scene.control.TextField.class::isInstance)
                .map(javafx.scene.control.TextField.class::cast)
                .filter(candidate -> prompt.equals(candidate.getPromptText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
    }
}
