package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.Theme;
import com.haichutieu.chatsystem.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDesignSystemFxTest {
    private static final String BASE = "com.haichutieu.chatsystem.desktop.admin.i18n.messages";

    @Test
    void loginUsesSharedThemeAndLocalizedFxmlWithoutInlineStyling() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));
            AdminAppContext context = new AdminAppContext(stage, "http://127.0.0.1:1", theme, vietnamese);
            try {
                Parent root = AdminViewLoader.load(AdminViewLoader.LOGIN,
                        new AdminLoginController(context, profile -> { }), vietnamese);
                Scene scene = new Scene(root, 520, 640);
                theme.apply(scene);
                AdminViewLoader.applyStylesheet(scene);
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();

                assertThat(root.getStyle()).isEmpty();
                assertThat(root.getStyleClass()).contains("login-root");
                assertThat(root.getStyleClass()).contains("theme-light");
                assertThat(scene.getStylesheets()).contains(ThemeManager.stylesheetUrl(),
                        AdminViewLoader.stylesheetUrl());
                assertThat(labels(root)).contains("Đăng nhập quản trị", "Quản trị Chat System");
                assertThat(scene.getRoot().lookupAll(".label").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .map(Label::getText).filter(text -> text != null)
                        .noneMatch(text -> text.contains("admin."))).isTrue();

                TextField username = field(scene, "Administrator username");
                PasswordField password = (PasswordField) field(scene, "Password");
                Button signIn = button(scene, "Đăng nhập");
                assertThat(username.getAccessibleText()).isNotBlank();
                assertThat(password.getAccessibleText()).isNotBlank();
                assertThat(signIn.isDefaultButton()).isTrue();
                assertThat(username.isFocusTraversable()).isTrue();
                assertThat(password.isFocusTraversable()).isTrue();
                assertThat(root.lookupAll(".field-label").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .anyMatch(label -> label.getLabelFor() == username)).isTrue();
                assertThat(root.lookupAll(".button").stream()
                        .filter(Button.class::isInstance).map(Button.class::cast)
                        .allMatch(candidate -> candidate.getText() != null
                                && !candidate.getText().isBlank())).isTrue();

                Paint light = signIn.getTextFill();
                theme.setTheme(Theme.DARK);
                theme.apply(scene);
                root.applyCss();
                assertThat(root.getStyleClass()).contains("theme-dark");
                assertThat(signIn.getTextFill()).isNotEqualTo(light);
                return true;
            } finally {
                context.close();
                stage.close();
            }
        })).isTrue();
    }


    @Test
    void accountAndReportScreensKeepLabelsFocusAndBundleContractInBothLocales() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer()) {
            assertThat(onFx(() -> {
                Stage stage = new Stage();
                ThemeManager theme = new ThemeManager();
                theme.setTheme(Theme.LIGHT);
                I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));
                AdminAppContext context = new AdminAppContext(stage, server.origin().toString(), theme, vietnamese);
                try {
                    context.activate(context.signIn("admin", "correct horse battery staple".toCharArray()));
                    Parent accounts = AdminViewLoader.load(AdminViewLoader.ACCOUNTS,
                            new AccountsController(context), vietnamese);
                    Scene accountsScene = new Scene(accounts, 1280, 720);
                    theme.apply(accountsScene);
                    AdminViewLoader.applyStylesheet(accountsScene);
                    stage.setScene(accountsScene);
                    stage.show();
                    accounts.applyCss();
                    accounts.layout();

                    ResourceBundle vi = vietnamese.bundle();
                    assertThat(labels(accounts)).contains(
                            vi.getString("admin.accounts.loginHistoryTitle"),
                            vi.getString("admin.accounts.friendListTitle"));
                    assertThat(buttonTexts(accounts)).contains(
                            vi.getString("admin.accounts.newUser"),
                            vi.getString("admin.accounts.resetPassword"));
                    TextField username = (TextField) accounts.lookup("#usernameField");
                    TextField fullName = (TextField) accounts.lookup("#fullNameField");
                    DatePicker birthDate = (DatePicker) accounts.lookup("#birthDateField");
                    ComboBox<?> gender = (ComboBox<?>) accounts.lookup("#genderField");
                    assertThat(username.getAccessibleText()).isNotBlank();
                    assertThat(fullName.getAccessibleText()).isNotBlank();
                    assertThat(((TextField) accounts.lookup("#filterField")).getAccessibleText())
                            .isNotBlank();
                    assertThat(accounts.lookupAll(".field-label").stream()
                            .filter(Label.class::isInstance).map(Label.class::cast)
                            .anyMatch(label -> label.getLabelFor() == username)).isTrue();
                    assertThat(accounts.lookupAll(".field-label").stream()
                            .filter(Label.class::isInstance).map(Label.class::cast)
                            .anyMatch(label -> label.getLabelFor() == fullName)).isTrue();
                    assertThat(List.of(username, fullName, birthDate, gender))
                            .allMatch(node -> node.isFocusTraversable());
                    assertThat(((Button) accounts.lookup("#saveButton")).isDefaultButton()).isTrue();
                    assertThat(((TableView<?>) accounts.lookup("#historyTable")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((TableView<?>) accounts.lookup("#friendsTable")).getAccessibleText())
                            .isNotBlank();
                    assertThat(accounts.lookupAll(".button").stream()
                            .filter(Button.class::isInstance).map(Button.class::cast)
                            .allMatch(candidate -> candidate.getText() != null
                                    && !candidate.getText().isBlank())).isTrue();

                    I18n english = new I18n(BASE, Locale.ENGLISH);
                    Parent reports = AdminViewLoader.load(AdminViewLoader.REPORTS,
                            new ReportsController(context), english);
                    Scene reportsScene = new Scene(reports, 1280, 720);
                    theme.apply(reportsScene);
                    AdminViewLoader.applyStylesheet(reportsScene);
                    stage.setScene(reportsScene);
                    reports.applyCss();
                    reports.layout();
                    ResourceBundle en = english.bundle();
                    assertThat(labels(reports)).contains(
                            vi.getString("admin.reports.evidenceTitle"),
                            en.getString("admin.reports.evidence"));
                    assertThat(((CheckBox) reports.lookup("#lockOnUphold")).getText())
                            .isEqualTo(en.getString("admin.reports.lockOnUphold"));
                    assertThat(((TextField) reports.lookup("#reportedUsernameField")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((DatePicker) reports.lookup("#fromDate")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((DatePicker) reports.lookup("#toDate")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((TextField) reports.lookup("#noteField")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((TableView<?>) reports.lookup("#reportsTable")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((TableView<?>) reports.lookup("#evidenceTable")).getAccessibleText())
                            .isNotBlank();
                    assertThat(reports.lookupAll(".button").stream()
                            .filter(Button.class::isInstance).map(Button.class::cast)
                            .allMatch(candidate -> candidate.getText() != null
                                    && !candidate.getText().isBlank())).isTrue();

                    for (String key : List.of(
                            "admin.accounts.newUser", "admin.accounts.edit",
                            "admin.accounts.save", "admin.accounts.resetPassword",
                            "admin.accounts.delete", "admin.accounts.deleteConfirm",
                            "admin.accounts.loginHistoryTitle", "admin.accounts.friendListTitle",
                            "admin.accounts.friendListEmptyHint", "admin.accounts.emailInvalid",
                            "admin.reports.evidence", "admin.reports.evidenceView",
                            "admin.reports.evidenceTitle", "admin.reports.evidenceEmptyHint",
                            "admin.reports.evidenceFor")) {
                        assertThat(vi.getString(key)).as("VI key %s", key).isNotBlank();
                        assertThat(en.getString(key)).as("EN key %s", key).isNotBlank();
                    }
                    return true;
                } finally {
                    context.close();
                    stage.close();
                }
            })).isTrue();
        }
    }

    private static java.util.List<String> buttonTexts(Parent root) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .map(Button::getText).filter(java.util.Objects::nonNull).toList();
    }

    private static java.util.List<String> labels(Parent root) {
        return root.lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .map(Label::getText).filter(java.util.Objects::nonNull).toList();
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing field: " + prompt));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
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
