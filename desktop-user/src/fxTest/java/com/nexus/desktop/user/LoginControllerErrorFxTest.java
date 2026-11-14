package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;

/**
 * The sign-in screen only had its happy path. A request that never reaches the service arrives as an
 * {@link com.nexus.sdk.ApiException} wrapping the {@link java.net.ConnectException}, so the assertions
 * pin the text a person actually sees for a service that is not there.
 */
class LoginControllerErrorFxTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    @Test
    void showsTheShellMessageWiresTheLinksAndRejectsAnEmptyForm() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        AtomicBoolean registered = new AtomicBoolean();
        AtomicBoolean forgotPassword = new AtomicBoolean();
        Stage stage = onFx(Stage::new);
        UserAppContext context = new UserAppContext(stage, UNREACHABLE, false, null,
                new ThemeManager(), i18n);
        try {
            Parent root = onFx(() -> show(stage, i18n, new LoginController(context, profile -> { },
                    () -> registered.set(true), () -> forgotPassword.set(true), "Welcome back")));
            assertThat(feedback(root).getText()).as("the message the shell passed in is shown first")
                    .isEqualTo("Welcome back");

            boolean darkBefore = context.themeManager().isDark();
            onFx(() -> {
                fire(root, "#themeButton");
                return true;
            });
            assertThat(context.themeManager().isDark()).as("the theme button toggles the theme")
                    .isEqualTo(!darkBefore);

            onFx(() -> {
                fire(root, "#registerButton");
                fire(root, "#forgotPasswordButton");
                fire(root, "#signInButton");
                return true;
            });
            assertThat(registered).as("register is wired").isTrue();
            assertThat(forgotPassword).as("the reset link is wired").isTrue();
            assertThat(feedback(root).getText()).as("an empty form is rejected before any request")
                    .isEqualTo(i18n.get("login.required"));
            assertThat(feedback(root).getStyleClass()).contains("error");
        } finally {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
        }
    }

    @Test
    void explainsAnUnreachableServiceAndLeavesTheFormUsable() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        AtomicBoolean signedIn = new AtomicBoolean();
        Stage stage = onFx(Stage::new);
        UserAppContext context = new UserAppContext(stage, UNREACHABLE, false, null,
                new ThemeManager(), i18n);
        try {
            Parent root = onFx(() -> show(stage, i18n,
                    new LoginController(context, profile -> signedIn.set(true))));

            onFx(() -> {
                ((TextField) root.lookup("#usernameField")).setText("alice");
                ((PasswordField) root.lookup("#passwordField"))
                        .setText("correct horse battery staple");
                fire(root, "#signInButton");
                return true;
            });

            waitForFx(() -> !((Button) root.lookup("#signInButton")).isDisabled(),
                    Duration.ofSeconds(20));
            assertThat(feedback(root).getText()).as("a failed login explains itself")
                    .isEqualTo(i18n.get("error.unavailable"));
            assertThat(feedback(root).getStyleClass()).contains("error");
            assertThat(((PasswordField) root.lookup("#passwordField")).getText())
                    .as("the password is cleared once the attempt failed").isEmpty();
            assertThat(signedIn).as("a failed login never signs the person in").isFalse();
        } finally {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
        }
    }

    @Test
    void togglesTheThemeBeforeTheFormIsInAScene() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        Stage stage = onFx(Stage::new);
        UserAppContext context = new UserAppContext(stage, UNREACHABLE, false, null,
                new ThemeManager(), i18n);
        try {
            assertThat(onFx(() -> {
                LoginController controller = new LoginController(context, profile -> { });
                Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, i18n);
                boolean darkBefore = context.themeManager().isDark();
                fire(root, "#themeButton");
                return context.themeManager().isDark() != darkBefore;
            })).as("the toggle must not need a scene to be attached").isTrue();
        } finally {
            onFx(() -> {
                context.close();
                return true;
            });
        }
    }

    private static Parent show(Stage stage, I18n i18n, LoginController controller) {
        Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, i18n);
        stage.setScene(new Scene(root, 520, 640));
        stage.show();
        root.applyCss();
        root.layout();
        return root;
    }

    private static Label feedback(Parent root) {
        return (Label) root.lookup("#feedbackLabel");
    }

    private static void fire(Parent root, String id) {
        ((Button) root.lookup(id)).fire();
    }
}
