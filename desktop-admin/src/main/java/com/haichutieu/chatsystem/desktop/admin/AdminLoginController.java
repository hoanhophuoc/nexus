package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.model.MyProfile;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Arrays;
import java.util.function.Consumer;

/** FXML controller for the administrator authentication screen. */
public final class AdminLoginController {
    private final AdminAppContext context;
    private final Consumer<MyProfile> onSignedIn;

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label usernameLabel;
    @FXML private Label passwordLabel;
    @FXML private Label feedbackLabel;
    @FXML private Button signInButton;
    @FXML private Button themeButton;

    AdminLoginController(AdminAppContext context, Consumer<MyProfile> onSignedIn) {
        this.context = context;
        this.onSignedIn = onSignedIn;
    }

    @FXML
    private void initialize() {
        I18n i18n = context.i18n();
        usernameLabel.setLabelFor(usernameField);
        passwordLabel.setLabelFor(passwordField);
        usernameField.setAccessibleText(i18n.get("admin.login.usernamePrompt"));
        passwordField.setAccessibleText(i18n.get("admin.login.passwordPrompt"));
        feedbackLabel.setText(i18n.get("admin.login.hint"));
        usernameField.setOnAction(event -> passwordField.requestFocus());
        passwordField.setOnAction(event -> signIn());
        signInButton.setOnAction(event -> signIn());
        themeButton.setOnAction(event -> {
            context.themeManager().toggle();
            applyTheme();
            updateThemeButton();
        });
        updateThemeButton();
    }

    private void signIn() {
        I18n i18n = context.i18n();
        String username = usernameField.getText() == null ? "" : usernameField.getText().strip();
        String passwordText = passwordField.getText();
        if (username.isBlank() || passwordText == null || passwordText.isBlank()) {
            showError(i18n.get("admin.login.required"));
            return;
        }
        char[] password = passwordText.toCharArray();
        passwordField.clear();
        setBusy(true, i18n.get("admin.login.signingIn"));
        context.runAsync(() -> {
            try {
                return context.signIn(username, password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }, signedIn -> {
            context.activate(signedIn);
            setBusy(false, i18n.get("admin.login.hint"));
            onSignedIn.accept(signedIn.profile());
        }, exception -> {
            setBusy(false, i18n.get("admin.login.hint"));
            showError(AdminErrors.loginMessage(exception, i18n));
            passwordField.clear();
        });
    }

    private void setBusy(boolean busy, String message) {
        signInButton.setDisable(busy);
        usernameField.setDisable(busy);
        passwordField.setDisable(busy);
        feedbackLabel.getStyleClass().remove("error");
        feedbackLabel.setText(message);
    }

    private void showError(String message) {
        feedbackLabel.setText(message);
        if (!feedbackLabel.getStyleClass().contains("error")) {
            feedbackLabel.getStyleClass().add("error");
        }
    }

    private void applyTheme() {
        Scene scene = signInButton.getScene();
        if (scene != null) context.themeManager().apply(scene);
    }

    private void updateThemeButton() {
        boolean dark = context.themeManager().isDark();
        themeButton.setText(context.i18n().get(dark ? "admin.theme.iconLight" : "admin.theme.iconDark"));
        themeButton.setAccessibleText(context.i18n().get(
                dark ? "admin.theme.toLight" : "admin.theme.toDark"));
    }
}
