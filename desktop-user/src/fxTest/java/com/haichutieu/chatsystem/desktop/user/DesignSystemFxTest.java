package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.Theme;
import com.haichutieu.chatsystem.desktop.shared.ui.ThemeManager;
import com.haichutieu.chatsystem.desktop.shared.ui.UiComponents;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DesignSystemFxTest {
    private static final String BASE = "com.haichutieu.chatsystem.desktop.user.i18n.messages";

    @Test
    void appliesLightAndDarkThemeTokensThroughTheSharedStylesheet() {
        assertThat(onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            Label title = new Label("Title");
            title.getStyleClass().add("title");
            VBox root = new VBox(title);
            Scene scene = new Scene(root, 320, 200);
            theme.apply(scene);
            root.applyCss();
            root.layout();
            Paint light = title.getTextFill();

            assertThat(root.getStyleClass()).contains("theme-light");
            assertThat(scene.getStylesheets()).contains(ThemeManager.stylesheetUrl());

            theme.setTheme(Theme.DARK);
            theme.apply(scene);
            root.applyCss();
            assertThat(root.getStyleClass()).contains("theme-dark");
            assertThat(title.getTextFill()).isNotEqualTo(light);
            return true;
        })).isTrue();
    }

    @Test
    void sharedComponentsExposeAccessibleNamesAndStateVariants() {
        assertThat(onFx(() -> {
            StackPane avatar = UiComponents.avatar("Alice Example", 40);
            assertThat(avatar.getAccessibleText()).isEqualTo("Alice Example");
            Label initials = (Label) avatar.getChildren().getFirst();
            assertThat(initials.getText()).isEqualTo("AE");

            Circle online = UiComponents.presenceDot(true, "Online");
            assertThat(online.getAccessibleText()).isEqualTo("Online");
            assertThat(online.getStyleClass()).contains("presence-dot", "online");

            VBox empty = UiComponents.emptyState("Empty", "Nothing here", "Retry", () -> { });
            assertThat(empty.getStyleClass()).contains("state-view");
            assertThat(empty.getChildren()).hasSize(3);

            VBox loading = UiComponents.loadingState("Loading");
            assertThat(loading.getChildren().getFirst()).isInstanceOf(ProgressIndicator.class);

            VBox error = UiComponents.errorState("Error", "Try again", "Retry", () -> { });
            assertThat(error.getStyleClass()).contains("error-state");
            return true;
        })).isTrue();
    }

    @Test
    void focusRingAndMinimumTargetSizesAreApplied() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            Button primary = new Button("Primary");
            primary.getStyleClass().add("primary-button");
            CheckBox consent = new CheckBox("Consent");
            VBox root = new VBox(8, primary, consent);
            Scene scene = new Scene(root, 320, 160);
            theme.apply(scene);
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();
                assertThat(primary.getBoundsInLocal().getWidth()).isGreaterThanOrEqualTo(24);
                assertThat(primary.getBoundsInLocal().getHeight()).isGreaterThanOrEqualTo(24);
                assertThat(consent.getBoundsInLocal().getWidth()).isGreaterThanOrEqualTo(24);
                assertThat(consent.getBoundsInLocal().getHeight()).isGreaterThanOrEqualTo(24);

                PseudoClass focused = PseudoClass.getPseudoClass("focused");
                primary.pseudoClassStateChanged(focused, true);
                consent.pseudoClassStateChanged(focused, true);
                root.applyCss();
                assertThat(primary.getEffect()).as("primary-button focus ring").isNotNull();
                assertThat(consent.getEffect()).as("check-box focus ring").isNotNull();

                theme.setTheme(Theme.DARK);
                theme.apply(scene);
                root.applyCss();
                assertThat(primary.getEffect()).as("dark primary-button focus ring").isNotNull();
                return true;
            } finally {
                stage.close();
            }
        })).isTrue();
    }

    @Test
    void loginViewLoadsFromFxmlWithVietnameseCopyAndStableAccessibility() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));
            UserAppContext context = new UserAppContext(stage, "http://127.0.0.1:1",
                    false, null, theme, vietnamese);
            try {
                LoginController controller = new LoginController(context, profile -> { });
                Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, vietnamese);
                Scene scene = new Scene(root, 520, 640);
                theme.apply(scene);
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();

                assertThat(root.getStyle()).isEmpty();
                assertThat(root.getStyleClass()).contains("login-root");
                assertThat(root.getStyleClass()).contains(theme.isDark() ? "theme-dark" : "theme-light");
                assertThat(scene.getStylesheets()).contains(ThemeManager.stylesheetUrl());
                assertThat(root.lookupAll(".label").stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .map(Label::getText)
                        .anyMatch("Chào mừng trở lại"::equals)).isTrue();

                TextField username = field(scene, "Username");
                PasswordField password = (PasswordField) field(scene, "Password");
                Button signIn = button(scene, "Sign in");
                assertThat(username.isFocusTraversable()).isTrue();
                assertThat(password.isFocusTraversable()).isTrue();
                assertThat(signIn.isDefaultButton()).isTrue();
                assertThat(root.lookupAll(".field-label").stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .anyMatch(label -> label.getLabelFor() == username)).isTrue();
                assertThat(root.lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .allMatch(candidate -> candidate.getText() != null
                                && !candidate.getText().isBlank())).isTrue();
                return true;
            } finally {
                context.close();
                stage.close();
            }
        })).isTrue();
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing field: " + prompt));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + text));
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
