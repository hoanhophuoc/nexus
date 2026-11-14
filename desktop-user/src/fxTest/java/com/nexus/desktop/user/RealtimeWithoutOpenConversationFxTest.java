package com.nexus.desktop.user;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;

/**
 * A realtime event can arrive while nothing is open: the first conversation list has not loaded yet,
 * or the open group was deleted. The shell has to ignore that event instead of repainting from a
 * selection that is gone. Signing in against a server with no conversations keeps the shell in
 * exactly that state for the whole test, so the path is reached on purpose rather than by timing.
 */
class RealtimeWithoutOpenConversationFxTest {

    @Test
    void ignoresARealtimeEventWhenNoConversationIsOpen(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer()) {
            server.hideDirectConversation();
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
                // The realtime listener repaints on connect, which is a repaint with nothing open.
                realtime.awaitClient(TimeUnit.SECONDS, 10);
                waitForFx(() -> conversationCount(stage) == 0, Duration.ofSeconds(10));

                realtime.sendText("{\"type\":\"MessageCreated\",\"conversationId\":\""
                        + FakeChatServer.FRIEND_ID + "\",\"message\":{\"id\":\"" + UUID.randomUUID()
                        + "\",\"conversationId\":\"" + FakeChatServer.FRIEND_ID + "\",\"senderId\":\""
                        + FakeChatServer.FRIEND_ID + "\",\"content\":\"late\",\"createdAt\":"
                        + "\"2026-11-14T10:00:00Z\"}}");

                // The shell is still on its empty state and still answers: no repaint from nowhere.
                waitForFx(() -> stage.getScene() != null, Duration.ofSeconds(10));
                assertThat(onFx(() -> conversationCount(stage)))
                        .as("an event for a conversation nobody has open opens nothing")
                        .isZero();
                assertThat(onFx(() -> button(stage, "Send") != null))
                        .as("the shell is still responsive").isTrue();
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

    private static int conversationCount(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return 0;
        return (int) scene.getRoot().lookupAll(".list-view").stream()
                .filter(ListView.class::isInstance)
                .map(node -> (ListView<?>) node)
                .flatMap(list -> list.getItems().stream())
                .filter(conversation -> conversation instanceof com.nexus.sdk.model.Conversation)
                .count();
    }

    private static Button button(Stage stage, String text) {
        return stage.getScene() == null ? null : button(stage.getScene(), text);
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElse(null);
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(candidate -> prompt.equals(candidate.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
    }
}
