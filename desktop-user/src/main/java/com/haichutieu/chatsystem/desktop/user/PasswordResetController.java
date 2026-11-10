package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.function.Consumer;

/** FXML controller for requesting and confirming a password reset. */
public final class PasswordResetController {
    private final UserAppContext context;
    private final Consumer<String> onReset;
    private final Runnable onBack;
    private final I18n i18n;

    @FXML private Label emailLabel;
    @FXML private TextField emailField;
    @FXML private Button requestButton;
    @FXML private VBox confirmSection;
    @FXML private Label tokenLabel;
    @FXML private TextField tokenField;
    @FXML private Label newPasswordLabel;
    @FXML private PasswordField newPasswordField;
    @FXML private Label confirmPasswordLabel;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button resetButton;
    @FXML private Label feedbackLabel;
    @FXML private Button backButton;

    PasswordResetController(UserAppContext context, Consumer<String> onReset, Runnable onBack) {
        this.context = context;
        this.i18n = context.i18n();
        this.onReset = onReset;
        this.onBack = onBack;
    }

    @FXML
    private void initialize() {
        emailLabel.setLabelFor(emailField);
        tokenLabel.setLabelFor(tokenField);
        newPasswordLabel.setLabelFor(newPasswordField);
        confirmPasswordLabel.setLabelFor(confirmPasswordField);
        emailField.setAccessibleText(i18n.get("reset.email"));
        tokenField.setAccessibleText(i18n.get("reset.token"));
        newPasswordField.setAccessibleText(i18n.get("reset.newPassword"));
        confirmPasswordField.setAccessibleText(i18n.get("reset.confirmPassword"));
        requestButton.setOnAction(event -> request());
        resetButton.setOnAction(event -> confirm());
        backButton.setOnAction(event -> onBack.run());
    }

    private void request() {
        String email = text(emailField);
        if (email.isBlank() || !email.contains("@")) {
            showError(i18n.get("reset.emailInvalid"));
            return;
        }
        requestButton.setDisable(true);
        feedbackLabel.getStyleClass().remove("error");
        feedbackLabel.setText(i18n.get("reset.sending"));
        context.runAsync(() -> {
            context.requestPasswordReset(email);
            return null;
        }, ignored -> {
            requestButton.setDisable(false);
            confirmSection.setVisible(true);
            confirmSection.setManaged(true);
            tokenField.requestFocus();
            feedbackLabel.setText(i18n.get("reset.sent"));
        }, exception -> {
            requestButton.setDisable(false);
            showError(UserErrors.statusMessage(exception, i18n));
        });
    }

    private void confirm() {
        String token = text(tokenField);
        String password = newPasswordField.getText();
        String confirmation = confirmPasswordField.getText();
        if (token.length() < 32) {
            showError(i18n.get("reset.tokenInvalid"));
            return;
        }
        if (password == null || password.length() < 12) {
            showError(i18n.get("reset.passwordShort"));
            return;
        }
        if (!password.equals(confirmation)) {
            showError(i18n.get("reset.passwordMismatch"));
            return;
        }
        char[] secret = password.toCharArray();
        newPasswordField.clear();
        confirmPasswordField.clear();
        resetButton.setDisable(true);
        feedbackLabel.setText(i18n.get("reset.saving"));
        context.runAsync(() -> {
            try {
                context.confirmPasswordReset(token, new String(secret));
                return null;
            } finally {
                Arrays.fill(secret, '\0');
            }
        }, ignored -> {
            resetButton.setDisable(false);
            feedbackLabel.setText(i18n.get("reset.done"));
            onReset.accept(i18n.get("reset.done"));
        }, exception -> {
            resetButton.setDisable(false);
            showError(UserErrors.statusMessage(exception, i18n));
        });
    }

    private void showError(String message) {
        feedbackLabel.setText(message);
        if (!feedbackLabel.getStyleClass().contains("error")) feedbackLabel.getStyleClass().add("error");
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().strip();
    }
}
