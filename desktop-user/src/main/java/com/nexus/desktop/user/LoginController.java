package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.sdk.model.MyProfile;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Arrays;
import java.util.function.Consumer;

/** FXML controller for the authentication screen. */
public final class LoginController {
    private final UserAppContext context;
    private final Consumer<MyProfile> onSignedIn;
    private final Runnable onRegister;
    private final Runnable onForgotPassword;
    private String initialMessage;

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label usernameLabel;
    @FXML private Label passwordLabel;
    @FXML private Label feedbackLabel;
    @FXML private Button signInButton;
    @FXML private Button registerButton;
    @FXML private Button forgotPasswordButton;
    @FXML private Button themeButton;

    LoginController(UserAppContext context, Consumer<MyProfile> onSignedIn) {
        this(context, onSignedIn, () -> { }, () -> { }, null);
    }

    LoginController(UserAppContext context, Consumer<MyProfile> onSignedIn,
                    Runnable onRegister, Runnable onForgotPassword, String initialMessage) {
        this.context = context;
        this.onSignedIn = onSignedIn;
        this.onRegister = onRegister;
        this.onForgotPassword = onForgotPassword;
        this.initialMessage = initialMessage;
    }

    @FXML
    private void initialize() {
        I18n i18n = context.i18n();
        feedbackLabel.setText(initialMessage == null ? i18n.get("login.hint") : initialMessage);
        usernameLabel.setLabelFor(usernameField);
        passwordLabel.setLabelFor(passwordField);
        usernameField.setAccessibleText(i18n.get("login.username"));
        passwordField.setAccessibleText(i18n.get("login.password"));
        usernameField.setOnAction(event -> passwordField.requestFocus());
        passwordField.setOnAction(event -> signIn());
        signInButton.setOnAction(event -> signIn());
        registerButton.setOnAction(event -> onRegister.run());
        forgotPasswordButton.setOnAction(event -> onForgotPassword.run());
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
            showError(i18n.get("login.required"));
            return;
        }

        char[] password = passwordText.toCharArray();
        passwordField.clear();
        setBusy(true, i18n.get("login.signingIn"));
        context.runAsync(() -> {
            try {
                return context.signIn(username, password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }, signedIn -> {
            context.activate(signedIn);
            setBusy(false, i18n.get("login.hint"));
            onSignedIn.accept(signedIn.profile());
        }, exception -> {
            setBusy(false, i18n.get("login.hint"));
            showError(UserErrors.loginMessage(exception, i18n));
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
        themeButton.setText(dark ? "☀" : "☾");
        themeButton.setAccessibleText(context.i18n().get(dark ? "theme.toLight" : "theme.toDark"));
    }
}
