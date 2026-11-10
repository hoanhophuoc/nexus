package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.model.Registration;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** FXML controller for account registration. */
public final class RegisterController {
    private final UserAppContext context;
    private final Consumer<String> onRegistered;
    private final Runnable onBack;
    private final I18n i18n;

    @FXML private Label usernameLabel;
    @FXML private Label fullNameLabel;
    @FXML private Label emailLabel;
    @FXML private Label addressLabel;
    @FXML private Label birthDateLabel;
    @FXML private Label genderLabel;
    @FXML private Label passwordLabel;
    @FXML private Label confirmPasswordLabel;
    @FXML private TextField usernameField;
    @FXML private TextField fullNameField;
    @FXML private TextField emailField;
    @FXML private TextField addressField;
    @FXML private DatePicker birthDateField;
    @FXML private ComboBox<GenderChoice> genderField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Label feedbackLabel;
    @FXML private Button createButton;
    @FXML private Button backButton;

    RegisterController(UserAppContext context, Consumer<String> onRegistered, Runnable onBack) {
        this.context = context;
        this.i18n = context.i18n();
        this.onRegistered = onRegistered;
        this.onBack = onBack;
    }

    @FXML
    private void initialize() {
        usernameLabel.setLabelFor(usernameField);
        fullNameLabel.setLabelFor(fullNameField);
        emailLabel.setLabelFor(emailField);
        addressLabel.setLabelFor(addressField);
        birthDateLabel.setLabelFor(birthDateField);
        genderLabel.setLabelFor(genderField);
        passwordLabel.setLabelFor(passwordField);
        confirmPasswordLabel.setLabelFor(confirmPasswordField);
        usernameField.setAccessibleText(i18n.get("register.username"));
        fullNameField.setAccessibleText(i18n.get("register.fullName"));
        emailField.setAccessibleText(i18n.get("register.email"));
        addressField.setAccessibleText(i18n.get("register.address"));
        birthDateField.setAccessibleText(i18n.get("register.birthDate"));
        genderField.setAccessibleText(i18n.get("register.gender"));
        passwordField.setAccessibleText(i18n.get("register.password"));
        confirmPasswordField.setAccessibleText(i18n.get("register.confirmPassword"));
        genderField.getItems().setAll(List.of(
                new GenderChoice(i18n.get("register.gender.female"), Registration.GenderEnum.FEMALE),
                new GenderChoice(i18n.get("register.gender.male"), Registration.GenderEnum.MALE),
                new GenderChoice(i18n.get("register.gender.other"), Registration.GenderEnum.OTHER),
                new GenderChoice(i18n.get("register.gender.undisclosed"), Registration.GenderEnum.UNDISCLOSED)));
        genderField.getSelectionModel().selectLast();
        birthDateField.setEditable(false);
        createButton.setOnAction(event -> submit());
        backButton.setOnAction(event -> onBack.run());
    }

    private void submit() {
        String username = text(usernameField);
        String fullName = text(fullNameField);
        String email = text(emailField);
        String address = text(addressField);
        LocalDate birthDate = birthDateField.getValue();
        GenderChoice gender = genderField.getValue();
        String password = passwordField.getText();
        String confirmation = confirmPasswordField.getText();
        if (username.isBlank() || fullName.isBlank() || email.isBlank() || birthDate == null
                || gender == null || password == null || password.isBlank()) {
            showError(i18n.get("register.required"));
            return;
        }
        if (!email.contains("@")) {
            showError(i18n.get("register.emailInvalid"));
            return;
        }
        if (password.length() < 12) {
            showError(i18n.get("register.passwordShort"));
            return;
        }
        if (!password.equals(confirmation)) {
            showError(i18n.get("register.passwordMismatch"));
            return;
        }
        char[] secret = password.toCharArray();
        Registration registration = new Registration().username(username).fullName(fullName)
                .email(email).address(address).birthDate(birthDate).gender(gender.value())
                .password(password);
        passwordField.clear();
        confirmPasswordField.clear();
        setBusy(true, i18n.get("register.creating"));
        context.runAsync(() -> {
            try {
                return context.register(registration);
            } finally {
                registration.setPassword(null);
                Arrays.fill(secret, '\0');
            }
        }, profile -> {
            setBusy(false, i18n.get("register.created"));
            onRegistered.accept(profile.getUsername());
        }, exception -> {
            setBusy(false, i18n.get("register.hint"));
            showError(UserErrors.statusMessage(exception, i18n));
            if (exception instanceof com.haichutieu.chatsystem.sdk.ApiException api && api.getCode() == 409) {
                showError(i18n.get("register.conflict"));
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        createButton.setDisable(busy);
        usernameField.setDisable(busy);
        fullNameField.setDisable(busy);
        emailField.setDisable(busy);
        addressField.setDisable(busy);
        birthDateField.setDisable(busy);
        genderField.setDisable(busy);
        passwordField.setDisable(busy);
        confirmPasswordField.setDisable(busy);
        feedbackLabel.getStyleClass().remove("error");
        feedbackLabel.setText(message);
    }

    private void showError(String message) {
        feedbackLabel.setText(message);
        if (!feedbackLabel.getStyleClass().contains("error")) feedbackLabel.getStyleClass().add("error");
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().strip();
    }

    private record GenderChoice(String label, Registration.GenderEnum value) {
        @Override
        public String toString() {
            return label;
        }
    }
}
