package com.nexus.desktop.user;

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
import static com.nexus.desktop.shared.testing.FxThread.onFx;

class UserDesktopApplicationFxTest {

    @Test
    void rendersLoginSceneAndAcceptsCredentialInput() {
        assertThat(onFx(() -> {
            UserDesktopApplication application = new UserDesktopApplication();
            Stage stage = new Stage();
            application.start(stage);
            try {
                Scene scene = stage.getScene();
                assertThat(scene).isNotNull();
                scene.getRoot().applyCss();
                scene.getRoot().layout();

                TextField username = field(scene, "Username");
                TextField password = field(scene, "Password");
                username.setText("alice");
                password.setText("correct horse battery staple");

                assertThat(username.getText()).isEqualTo("alice");
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

}
