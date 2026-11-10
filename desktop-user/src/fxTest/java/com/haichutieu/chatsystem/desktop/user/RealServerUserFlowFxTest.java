package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.SessionApi;
import com.haichutieu.chatsystem.sdk.model.Conversation;
import com.haichutieu.chatsystem.sdk.model.Login;
import com.haichutieu.chatsystem.sdk.model.Message;
import com.haichutieu.chatsystem.sdk.model.MessagePage;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test for a real local server.
 *
 * <pre>
 * CHAT_REAL_SERVER=true CHAT_REAL_USERNAME=alice_real CHAT_REAL_PASSWORD=... \
 *   DISPLAY=:0 ./gradlew :desktop-user:fxTest --tests '*RealServerUserFlowFxTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHAT_REAL_SERVER", matches = "true")
class RealServerUserFlowFxTest {

    @Test
    void logsInAndPersistsAMessageSentFromJavaFx(@TempDir Path home) throws Exception {
        String origin = env("CHAT_REAL_SERVER_ORIGIN", "http://127.0.0.1:8080");
        String username = env("CHAT_REAL_USERNAME", "alice_real");
        String password = System.getenv("CHAT_REAL_PASSWORD");
        assertThat(password).as("CHAT_REAL_PASSWORD").isNotBlank();

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<UserDesktopApplication> applicationReference = new AtomicReference<>();
        try {
            onFx(() -> {
                UserDesktopApplication application = new UserDesktopApplication(origin, false);
                applicationReference.set(application);
                Stage stage = new Stage();
                stageReference.set(stage);
                application.start(stage);
                return true;
            });
            Stage stage = stageReference.get();
            try {
                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Username").setText(username);
                    field(scene, "Password").setText(password);
                    button(scene, "Sign in").fire();
                    return true;
                });

                waitForFx(() -> button(stage, "Send") != null, Duration.ofSeconds(15));
                waitForFx(() -> hasConversation(stage), Duration.ofSeconds(15));
                onFx(() -> {
                    selectFirstConversation(stage);
                    return true;
                });

                String content = "real-javafx-" + UUID.randomUUID();
                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Write a message").setText(content);
                    button(scene, "Send").fire();
                    return true;
                });
                waitForFx(() -> labelContaining(stage, content), Duration.ofSeconds(15));

                assertThat(messagePersisted(origin, username, password, content)).isTrue();
            } finally {
                onFx(() -> {
                    stage.close();
                    applicationReference.get().stop();
                    return true;
                });
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static boolean messagePersisted(String origin, String username, String password,
                                            String content) throws Exception {
        SessionApi verifier = new SessionApi(URI.create(origin));
        try {
            verifier.api().getCsrfToken();
            verifier.api().login(new Login().username(username).password(password));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                for (Conversation conversation : verifier.api().listConversations(null, 100).getItems()) {
                    if (conversation.getType() != Conversation.TypeEnum.DIRECT) continue;
                    MessagePage page = verifier.api().getMessages(conversation.getId(), null, 100);
                    if (page != null && page.getItems() != null
                            && page.getItems().stream().map(Message::getContent).anyMatch(content::equals)) {
                        return true;
                    }
                }
                Thread.sleep(200);
            }
            return false;
        } finally {
            verifier.clear();
        }
    }

    private static boolean hasConversation(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".list-view").stream()
                .filter(ListView.class::isInstance)
                .map(node -> (ListView<?>) node)
                .flatMap(list -> list.getItems().stream())
                .anyMatch(Conversation.class::isInstance);
    }

    private static void selectFirstConversation(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return;
        for (javafx.scene.Node node : scene.getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list && !list.getItems().isEmpty()
                    && list.getItems().getFirst() instanceof Conversation) {
                list.getSelectionModel().clearAndSelect(0);
                return;
            }
        }
    }

    private static boolean labelContaining(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX button: " + text));
    }

    private static Button button(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return null;
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
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The scene can still be swapping from the login form to the chat shell.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the JavaFX UI.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the JavaFX UI to update.");
    }

    private static <T> T onFx(Callable<T> action) {
        startToolkit();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("JavaFX action failed", exception);
        }
    }

    private static synchronized void startToolkit() {
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start within 30 seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
