package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nexus.desktop.user.FxThread.onFx;
import static com.nexus.desktop.user.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reset screen rejects an address without an @, a short token, a short password and a mismatched
 * confirmation before it talks to the service, and it has to survive a service that is not there.
 */
class PasswordResetControllerErrorFxTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String PASSWORD = "correct horse battery";
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    @Test
    void rejectsBothStepsBeforeCallingTheService() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        AtomicBoolean wentBack = new AtomicBoolean();
        Stage stage = onFx(Stage::new);
        UserAppContext context = new UserAppContext(stage, UNREACHABLE, false, null,
                new ThemeManager(), i18n);
        try {
            Parent root = onFx(() -> show(stage, i18n, new PasswordResetController(context,
                    message -> { }, () -> wentBack.set(true))));

            assertRejected(root, i18n, "reset.emailInvalid", () -> {
                text(root, "#emailField").setText("not-an-address");
                fire(root, "#requestButton");
            });
            assertRejected(root, i18n, "reset.tokenInvalid", () -> {
                text(root, "#tokenField").setText("short");
                fire(root, "#resetButton");
            });
            assertRejected(root, i18n, "reset.passwordShort", () -> {
                text(root, "#tokenField").setText(TOKEN);
                text(root, "#newPasswordField").setText("too short");
                text(root, "#confirmPasswordField").setText("too short");
                fire(root, "#resetButton");
            });
            assertRejected(root, i18n, "reset.passwordMismatch", () -> {
                text(root, "#newPasswordField").setText(PASSWORD);
                text(root, "#confirmPasswordField").setText(PASSWORD + "X");
                fire(root, "#resetButton");
            });

            onFx(() -> {
                fire(root, "#backButton");
                return true;
            });
            assertThat(wentBack).as("the back link is wired").isTrue();
        } finally {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
        }
    }

    @Test
    void reportsAnUnreachableServiceOnBothSteps() throws Exception {
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        Stage stage = onFx(Stage::new);
        UserAppContext context = new UserAppContext(stage, UNREACHABLE, false, null,
                new ThemeManager(), i18n);
        try {
            Parent root = onFx(() -> show(stage, i18n,
                    new PasswordResetController(context, message -> { }, () -> { })));

            onFx(() -> {
                text(root, "#emailField").setText("alice@example.com");
                fire(root, "#requestButton");
                return true;
            });
            waitForFx(() -> !((Button) root.lookup("#requestButton")).isDisabled(),
                    Duration.ofSeconds(20));
            assertThat(feedback(root).getText()).as("a dead service explains that it is unavailable")
                    .isEqualTo(i18n.get("error.unavailable"));
            assertThat(((VBox) root.lookup("#confirmSection")).isVisible())
                    .as("the second step stays hidden when the request never reached the service")
                    .isFalse();

            onFx(() -> {
                text(root, "#tokenField").setText(TOKEN);
                text(root, "#newPasswordField").setText(PASSWORD);
                text(root, "#confirmPasswordField").setText(PASSWORD);
                fire(root, "#resetButton");
                return true;
            });
            waitForFx(() -> !((Button) root.lookup("#resetButton")).isDisabled(),
                    Duration.ofSeconds(20));
            assertThat(feedback(root).getText()).as("the second step reports the same failure")
                    .isEqualTo(i18n.get("error.unavailable"));
            assertThat(feedback(root).getStyleClass()).contains("error");
        } finally {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
        }
    }

    private static void assertRejected(Parent root, I18n i18n, String key, Runnable action) {
        onFx(() -> {
            action.run();
            return true;
        });
        assertThat(feedback(root).getText()).as(key).isEqualTo(i18n.get(key));
        assertThat(feedback(root).getStyleClass()).as(key).contains("error");
    }

    private static Parent show(Stage stage, I18n i18n, PasswordResetController controller) {
        Parent root = ViewLoader.load(ViewLoader.PASSWORD_RESET, controller, i18n);
        stage.setScene(new Scene(root, 520, 640));
        stage.show();
        root.applyCss();
        root.layout();
        return root;
    }

    private static Label feedback(Parent root) {
        return (Label) root.lookup("#feedbackLabel");
    }

    private static TextField text(Parent root, String id) {
        return (TextField) root.lookup(id);
    }

    private static void fire(Parent root, String id) {
        ((Button) root.lookup(id)).fire();
    }
}
