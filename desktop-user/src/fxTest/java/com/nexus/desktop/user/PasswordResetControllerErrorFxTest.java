package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reset screen rejects an address without an @, a short token and a mismatched confirmation before
 * it talks to the service, and it has to survive a service that is not reachable. None of those
 * branches had a test, so a person could have been sent a blank or wrong explanation.
 */
class PasswordResetControllerErrorFxTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String VALID_TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void validatesBothStepsBeforeCallingTheService() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        AtomicBoolean wentBack = new AtomicBoolean();
        Stage stage = onFx(Stage::new);
        UserAppContext context = onFx(() -> new UserAppContext(stage,
                "http://127.0.0.1:1", false, null, new ThemeManager(), i18n));
        try {
            Parent root = onFx(() -> {
                PasswordResetController controller = new PasswordResetController(context,
                        message -> { }, () -> wentBack.set(true));
                Parent loaded = ViewLoader.load(ViewLoader.PASSWORD_RESET, controller, i18n);
                Scene scene = new Scene(loaded, 520, 640);
                stage.setScene(scene);
                stage.show();
                loaded.applyCss();
                loaded.layout();
                return loaded;
            });

            assertThat(feedback(root, i18n.get("reset.emailInvalid"), "not-an-address",
                    () -> fire(root, "#requestButton")))
                    .as("an address without @ is rejected before the request")
                    .isTrue();

            assertThat(feedback(root, i18n.get("reset.tokenInvalid"), "",
                    () -> {
                        ((TextField) root.lookup("#tokenField")).setText("short");
                        fire(root, "#resetButton");
                    }))
                    .as("a token that is too short is rejected")
                    .isTrue();

            assertThat(feedback(root, i18n.get("reset.passwordShort"), "",
                    () -> {
                        ((TextField) root.lookup("#tokenField")).setText(VALID_TOKEN);
                        ((PasswordField) root.lookup("#newPasswordField")).setText("too short");
                        ((PasswordField) root.lookup("#confirmPasswordField")).setText("too short");
                        fire(root, "#resetButton");
                    }))
                    .as("a password under twelve characters is rejected")
                    .isTrue();

            assertThat(feedback(root, i18n.get("reset.passwordMismatch"), "",
                    () -> {
                        ((TextField) root.lookup("#tokenField")).setText(VALID_TOKEN);
                        ((PasswordField) root.lookup("#newPasswordField")).setText("correct horse battery");
                        ((PasswordField) root.lookup("#confirmPasswordField")).setText("correct horse batterY");
                        fire(root, "#resetButton");
                    }))
                    .as("a confirmation that differs is rejected")
                    .isTrue();

            fire(root, "#backButton");
            assertThat(wentBack).as("the back link is wired").isTrue();
        } finally {
            onFx(() -> {
                stage.close();
                context.network().shutdownNow();
                return true;
            });
        }
    }

    @Test
    void reportsAnUnreachableServiceOnBothSteps() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        Stage stage = onFx(Stage::new);
        UserAppContext context = onFx(() -> new UserAppContext(stage,
                "http://127.0.0.1:1", false, null, new ThemeManager(), i18n));
        try {
            Parent root = onFx(() -> {
                PasswordResetController controller = new PasswordResetController(context,
                        message -> { }, () -> { });
                Parent loaded = ViewLoader.load(ViewLoader.PASSWORD_RESET, controller, i18n);
                Scene scene = new Scene(loaded, 520, 640);
                stage.setScene(scene);
                stage.show();
                loaded.applyCss();
                loaded.layout();
                return loaded;
            });

            onFx(() -> {
                ((TextField) root.lookup("#emailField")).setText("alice@example.com");
                fire(root, "#requestButton");
                return true;
            });
            waitForFx(() -> errorShown(root), Duration.ofSeconds(20));
            assertThat(onFx(() -> ((VBox) root.lookup("#confirmSection")).isVisible()))
                    .as("the second step stays hidden when the request never reached the service")
                    .isFalse();

            onFx(() -> {
                ((TextField) root.lookup("#tokenField")).setText(VALID_TOKEN);
                ((PasswordField) root.lookup("#newPasswordField")).setText("correct horse battery");
                ((PasswordField) root.lookup("#confirmPasswordField")).setText("correct horse battery");
                fire(root, "#resetButton");
                return true;
            });
            waitForFx(() -> !((Button) root.lookup("#resetButton")).isDisabled(), Duration.ofSeconds(20));
            assertThat(onFx(() -> errorShown(root)))
                    .as("the failure is explained and the form is usable again")
                    .isTrue();
        } finally {
            onFx(() -> {
                stage.close();
                context.network().shutdownNow();
                return true;
            });
        }
    }

    private static boolean feedback(Parent root, String expected, String email, Runnable action) {
        return Boolean.TRUE.equals(onFx(() -> {
            ((TextField) root.lookup("#emailField")).setText(email);
            action.run();
            return errorShown(root, expected);
        }));
    }

    private static boolean errorShown(Parent root) {
        Label feedback = (Label) root.lookup("#feedbackLabel");
        return feedback.getStyleClass().contains("error") && !feedback.getText().isBlank();
    }

    private static boolean errorShown(Parent root, String expected) {
        Label feedback = (Label) root.lookup("#feedbackLabel");
        return feedback.getStyleClass().contains("error") && expected.equals(feedback.getText());
    }

    private static void fire(Parent root, String id) {
        ((Button) root.lookup(id)).fire();
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
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
            Platform.setImplicitExit(false);
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
