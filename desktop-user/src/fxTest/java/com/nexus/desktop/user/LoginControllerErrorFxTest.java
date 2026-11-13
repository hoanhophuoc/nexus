package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-in screen only had its happy path, so the branches a person actually hits when something
 * is wrong were never executed: an empty form, a login that fails because the service is unreachable,
 * the theme toggle and the links to register and to reset a password.
 */
class LoginControllerErrorFxTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";

    @Test
    void reportsAnEmptyFormAndAFailedLoginWithoutLeavingTheScreen() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        AtomicBoolean signedIn = new AtomicBoolean();
        AtomicBoolean registered = new AtomicBoolean();
        AtomicBoolean forgotPassword = new AtomicBoolean();
        Stage stage = onFx(Stage::new);
        UserAppContext context = onFx(() -> new UserAppContext(stage,
                "http://127.0.0.1:1", false, null, new ThemeManager(), i18n));
        try {
            onFx(() -> {
                LoginController controller = new LoginController(context, profile -> signedIn.set(true),
                        () -> registered.set(true), () -> forgotPassword.set(true), "Welcome back");
                Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, i18n);
                Scene scene = new Scene(root, 520, 640);
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();

                Label feedback = (Label) root.lookup("#feedbackLabel");
                assertThat(feedback.getText())
                        .as("the message the shell passed in is shown first")
                        .isEqualTo("Welcome back");

                boolean darkBefore = context.themeManager().isDark();
                ((Button) root.lookup("#themeButton")).fire();
                assertThat(context.themeManager().isDark())
                        .as("the theme button toggles the theme")
                        .isEqualTo(!darkBefore);
                ((Button) root.lookup("#registerButton")).fire();
                ((Button) root.lookup("#forgotPasswordButton")).fire();

                ((Button) root.lookup("#signInButton")).fire();
                return true;
            });

            assertThat(registered).as("register is wired").isTrue();
            assertThat(forgotPassword).as("the reset link is wired").isTrue();
            assertThat(onFx(() -> {
                Label feedback = (Label) stage.getScene().getRoot().lookup("#feedbackLabel");
                return feedback.getStyleClass().contains("error")
                        && feedback.getText().equals(i18n.get("login.required"));
            })).as("an empty form is rejected before any request").isTrue();

            onFx(() -> {
                Parent root = stage.getScene().getRoot();
                ((TextField) root.lookup("#usernameField")).setText("alice");
                ((PasswordField) root.lookup("#passwordField")).setText("correct horse battery staple");
                ((Button) root.lookup("#signInButton")).fire();
                return true;
            });

            waitForFx(() -> !((Button) stage.getScene().getRoot().lookup("#signInButton")).isDisabled(),
                    java.time.Duration.ofSeconds(20));
            assertThat(onFx(() -> {
                Label feedback = (Label) stage.getScene().getRoot().lookup("#feedbackLabel");
                return feedback.getStyleClass().contains("error") && !feedback.getText().isBlank();
            })).as("a failed login explains itself").isTrue();

            assertThat(onFx(() -> ((PasswordField) stage.getScene().getRoot().lookup("#passwordField"))
                    .getText())).as("the password is cleared once the attempt failed").isEmpty();
            assertThat(signedIn).as("a failed login never signs the person in").isFalse();
            assertThat(onFx(() -> ((Button) stage.getScene().getRoot().lookup("#signInButton")).isDisabled()))
                    .as("the form is usable again after the failure").isFalse();
        } finally {
            onFx(() -> {
                stage.close();
                context.network().shutdownNow();
                return true;
            });
        }
    }

    @Test
    void applyThemeIsSkippedWhenTheFormIsNotInAScene() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        Stage stage = onFx(Stage::new);
        UserAppContext context = onFx(() -> new UserAppContext(stage,
                "http://127.0.0.1:1", false, null, new ThemeManager(), i18n));
        try {
            assertThat(onFx(() -> {
                LoginController controller = new LoginController(context, profile -> { });
                Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, i18n);
                boolean darkBefore = context.themeManager().isDark();
                // no scene yet: the toggle must still work and skip the scene styling
                ((Button) root.lookup("#themeButton")).fire();
                return context.themeManager().isDark() != darkBefore;
            })).isTrue();
        } finally {
            onFx(() -> {
                context.network().shutdownNow();
                return true;
            });
        }
    }

    private static void waitForFx(BooleanSupplier condition, java.time.Duration timeout) {
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
