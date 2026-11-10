package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.sdk.model.Conversation;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class UserDesktopApplicationInteractionFxTest {

    @Test
    void signsInShowsConversationAndSendsMessage(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try (FakeChatServer server = new FakeChatServer()) {
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<UserDesktopApplication> applicationReference = new AtomicReference<>();
            onFx(() -> {
                UserDesktopApplication application =
                        new UserDesktopApplication(server.origin().toString(), false);
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
                waitForFx(() -> conversationNamed(stage, "Bob"), Duration.ofSeconds(10));
                assertThat(server.loginUsername()).isEqualTo("alice");
                assertThat(server.loginPassword()).isEqualTo("correct horse battery staple");
                assertThat(onFx(() -> conversationNamed(stage, "Bob"))).isTrue();

                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Write a message").setText("hello from JavaFX");
                    button(scene, "Send").fire();
                    return true;
                });

                waitForFx(() -> labelContaining(stage, "hello from JavaFX"),
                        Duration.ofSeconds(10));
                waitForFx(() -> server.sentContents().contains("hello from JavaFX"),
                        Duration.ofSeconds(10));
                assertThat(server.sentContents()).contains("hello from JavaFX");
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

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElse(null);
    }

    private static Button button(Stage stage, String text) {
        return stage.getScene() == null ? null : button(stage.getScene(), text);
    }

    private static boolean conversationNamed(Stage stage, String name) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".list-view").stream()
                .filter(ListView.class::isInstance)
                .map(node -> (ListView<?>) node)
                .flatMap(list -> list.getItems().stream())
                .filter(Conversation.class::isInstance)
                .map(Conversation.class::cast)
                .anyMatch(conversation -> name.equals(conversation.getName()));
    }

    private static boolean labelContaining(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
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
                Thread.sleep(50);
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
}
