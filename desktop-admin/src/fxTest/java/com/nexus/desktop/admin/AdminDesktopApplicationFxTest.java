package com.nexus.desktop.admin;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDesktopApplicationFxTest {

    @Test
    void rendersAdminLoginSceneAndAcceptsCredentialInput() {
        assertThat(onFx(() -> {
            AdminDesktopApplication application = new AdminDesktopApplication();
            Stage stage = new Stage();
            application.start(stage);
            try {
                Scene scene = stage.getScene();
                assertThat(scene).isNotNull();
                scene.getRoot().applyCss();
                scene.getRoot().layout();

                TextField username = field(scene, "Administrator username");
                TextField password = field(scene, "Password");
                username.setText("admin");
                password.setText("correct horse battery staple");

                assertThat(username.getText()).isEqualTo("admin");
                assertThat(password.getText()).isEqualTo("correct horse battery staple");
                assertThat(scene.getRoot().lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .anyMatch(button -> "Sign in".equals(button.getText()) && button.isDefaultButton()))
                        .isTrue();

                WritableImage snapshot = scene.snapshot(null);
                assertThat(snapshot.getWidth()).isPositive();
                assertThat(snapshot.getHeight()).isPositive();
                return true;
            } finally {
                stage.close();
                application.stop();
            }
        })).isTrue();
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
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
