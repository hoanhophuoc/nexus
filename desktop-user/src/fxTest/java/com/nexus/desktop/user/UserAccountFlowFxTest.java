package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.MyProfile;
import com.nexus.sdk.model.User;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Interaction coverage for the account, social, report and group-management screens. */
class UserAccountFlowFxTest {
    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void accountDiscoveryRequestsFriendsBlocksProfileAndPassword(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            try (FakeChatServer server = new FakeChatServer()) {
                server.addIncomingRequest(FakeChatServer.FRIEND_ID);
                Harness harness = harness(server);
                try {
                    Parent root = onFx(() -> {
                        AccountController controller = new AccountController(harness.context(),
                                id -> { }, user -> { }, () -> { }, () -> { });
                        Parent loaded = ViewLoader.load(ViewLoader.ACCOUNT, controller, harness.i18n());
                        Scene scene = new Scene(loaded, 960, 720);
                        harness.theme().apply(scene);
                        harness.stage().setScene(scene);
                        harness.stage().show();
                        return loaded;
                    });
                    Scene scene = harness.stage().getScene();
                    waitForFx(() -> listSize(scene, "#friendsList") == 1
                            && listSize(scene, "#incomingList") == 1, Duration.ofSeconds(10));
                    assertThat(((ListView<?>) scene.lookup("#discoveryList")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((ListView<?>) scene.lookup("#incomingList")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((ListView<?>) scene.lookup("#outgoingList")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((ListView<?>) scene.lookup("#friendsList")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((ListView<?>) scene.lookup("#blockedList")).getAccessibleText())
                            .isNotBlank();

                    onFx(() -> {
                        field(scene, "Search by username or name").setText("bob");
                        button(scene, "Search").fire();
                        return true;
                    });
                    waitForFx(() -> listSize(scene, "#discoveryList") == 1, Duration.ofSeconds(10));
                    onFx(() -> {
                        ((ListView<?>) scene.lookup("#discoveryList")).getSelectionModel().selectFirst();
                        button(scene, "Send friend request").fire();
                        return true;
                    });
                    waitFor(() -> server.outgoingRequestCount() == 1, Duration.ofSeconds(10));
                    waitForFx(() -> listSize(scene, "#outgoingList") == 1, Duration.ofSeconds(10));

                    onFx(() -> {
                        ((ListView<?>) scene.lookup("#outgoingList")).getSelectionModel().selectFirst();
                        button(scene, "Cancel request").fire();
                        return true;
                    });
                    waitFor(() -> server.outgoingRequestCount() == 0, Duration.ofSeconds(10));

                    onFx(() -> {
                        ((ListView<?>) scene.lookup("#incomingList")).getSelectionModel().selectFirst();
                        button(scene, "Accept").fire();
                        return true;
                    });
                    waitFor(() -> server.incomingRequestCount() == 0, Duration.ofSeconds(10));

                    onFx(() -> {
                        TabPane tabs = (TabPane) scene.lookup("#tabs");
                        tabs.getSelectionModel().select(4);
                        tabs.applyCss();
                        tabs.layout();
                        return true;
                    });
                    Button unfriend = button(scene, "Unfriend");
                    waitForFx(unfriend::isVisible, Duration.ofSeconds(10));
                    begin(() -> {
                        ((ListView<?>) scene.lookup("#friendsList")).getSelectionModel().selectFirst();
                        unfriend.requestFocus();
                        unfriend.fire();
                    });
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(false);
                    assertThat(server.friendRemovals()).isZero();
                    waitForFx(unfriend::isFocused, Duration.ofSeconds(10));
                    begin(() -> button(scene, "Unfriend").fire());
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(true);
                    waitFor(() -> server.friendRemovals() == 1, Duration.ofSeconds(10));

                    begin(() -> {
                        ((ListView<?>) scene.lookup("#friendsList")).getSelectionModel().selectFirst();
                        button(scene, "Block").fire();
                    });
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(false);
                    assertThat(server.blockedUsers()).isEmpty();
                    begin(() -> button(scene, "Block").fire());
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(true);
                    waitFor(() -> server.blockedUsers().contains(FakeChatServer.FRIEND_ID),
                            Duration.ofSeconds(10));
                    waitForFx(() -> labelText(scene, "#statusLabel")
                            .equals("Account is up to date."), Duration.ofSeconds(10));

                    onFx(() -> {
                        ((ListView<?>) scene.lookup("#discoveryList")).getSelectionModel().selectFirst();
                        button(scene, "Send friend request").fire();
                        return true;
                    });
                    waitFor(() -> server.blockedRequestAttempts() == 1, Duration.ofSeconds(10));
                    waitForFx(() -> labelText(scene, "#statusLabel")
                            .contains("blocked or already connected"), Duration.ofSeconds(10));
                    assertThat(server.outgoingRequestCount()).isZero();

                    onFx(() -> {
                        ((ListView<?>) scene.lookup("#blockedList")).getSelectionModel().selectFirst();
                        button(scene, "Unblock").fire();
                        return true;
                    });
                    waitFor(() -> server.blockedUsers().isEmpty(), Duration.ofSeconds(10));

                    onFx(() -> {
                        field(scene, "Full name").setText("Alice Updated");
                        button(scene, "Save profile").fire();
                        return true;
                    });
                    waitFor(() -> "Alice Updated".equals(server.profileFullName()), Duration.ofSeconds(10));

                    onFx(() -> {
                        ((PasswordField) scene.lookup("#currentPasswordField"))
                                .setText("not the current password");
                        ((PasswordField) scene.lookup("#newPasswordField")).setText("new password 12345");
                        ((PasswordField) scene.lookup("#confirmPasswordField")).setText("new password 12345");
                        button(scene, "Change password").fire();
                        return true;
                    });
                    waitForFx(() -> labelText(scene, "#statusLabel")
                            .contains("current password"), Duration.ofSeconds(10));
                    assertThat(server.passwordChanges()).isZero();
                    onFx(() -> {
                        ((PasswordField) scene.lookup("#currentPasswordField")).setText(PASSWORD);
                        ((PasswordField) scene.lookup("#newPasswordField")).setText("new password 12345");
                        ((PasswordField) scene.lookup("#confirmPasswordField")).setText("new password 12345");
                        button(scene, "Change password").fire();
                        return true;
                    });
                    waitFor(() -> server.passwordChanges() == 1, Duration.ofSeconds(10));
                    assertThat(onFx(() -> root.getStyle()).isEmpty()).isTrue();
                } finally {
                    onFx(() -> {
                        harness.stage().close();
                        harness.context().close();
                        return true;
                    });
                }
            }
            return null;
        });
    }

    @Test
    void reportSubmitsSelectedEvidence(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            try (FakeChatServer server = new FakeChatServer()) {
                Harness harness = harness(server);
                try {
                    User target = new User().id(FakeChatServer.FRIEND_ID).username("bob").fullName("Bob");
                    CachedMessage evidence = new CachedMessage(UUID.randomUUID(),
                            FakeChatServer.CONVERSATION_ID, FakeChatServer.FRIEND_ID.toString(),
                            "the reported message", Instant.now());
                    onFx(() -> {
                        ReportController controller = new ReportController(harness.context(), target,
                                List.of(evidence), () -> { });
                        Parent root = ViewLoader.load(ViewLoader.REPORT, controller, harness.i18n());
                        Scene scene = new Scene(root, 560, 620);
                        harness.theme().apply(scene);
                        harness.stage().setScene(scene);
                        harness.stage().show();
                        return true;
                    });
                    Scene scene = harness.stage().getScene();
                    assertThat(((javafx.scene.control.TextArea) scene.lookup("#reasonField"))
                            .getAccessibleText()).isNotBlank();
                    assertThat(((ListView<?>) scene.lookup("#evidenceList")).getAccessibleText())
                            .isNotBlank();
                    onFx(() -> {
                        ((javafx.scene.control.TextArea) scene.lookup("#reasonField"))
                                .setText("Harassment");
                        ((ListView<?>) scene.lookup("#evidenceList")).getSelectionModel().selectFirst();
                        ((javafx.scene.control.CheckBox) scene.lookup("#consentCheck")).setSelected(true);
                        button(scene, "Submit report").fire();
                        return true;
                    });
                    waitFor(() -> server.reports() == 1, Duration.ofSeconds(10));
                    assertThat(server.reportReason()).isEqualTo("Harassment");
                    assertThat(server.reportsWithEvidence()).isEqualTo(1);
                } finally {
                    onFx(() -> {
                        harness.stage().close();
                        harness.context().close();
                        return true;
                    });
                }
            }
            return null;
        });
    }

    @Test
    void groupSettingsRenamePromoteRemoveAndLeaveWithConfirmations(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            try (FakeChatServer server = new FakeChatServer()) {
                Harness harness = harness(server);
                try {
                    Conversation group = harness.context().groups().createGroup("Team",
                            List.of(FakeChatServer.FRIEND_ID));
                    onFx(() -> {
                        GroupSettingsController controller = new GroupSettingsController(harness.context(),
                                group, () -> { }, () -> { }, () -> { });
                        Parent root = ViewLoader.load(ViewLoader.GROUP_SETTINGS, controller,
                                harness.i18n());
                        Scene scene = new Scene(root, 640, 600);
                        harness.theme().apply(scene);
                        harness.stage().setScene(scene);
                        harness.stage().show();
                        return true;
                    });
                    Scene scene = harness.stage().getScene();
                    waitForFx(() -> listSize(scene, "#membersList") == 2, Duration.ofSeconds(10));
                    assertThat(((ListView<?>) scene.lookup("#membersList")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((TextField) scene.lookup("#nameField")).getAccessibleText())
                            .isNotBlank();

                    onFx(() -> {
                        field(scene, "Group name").setText("Renamed team");
                        button(scene, "Rename").fire();
                        return true;
                    });
                    waitFor(() -> "Renamed team".equals(server.groupName()), Duration.ofSeconds(10));

                    onFx(() -> {
                        ((ListView<?>) scene.lookup("#membersList")).getSelectionModel().select(1);
                        button(scene, "Promote").fire();
                        return true;
                    });
                    waitFor(() -> "admin".equals(server.memberRoles().get(FakeChatServer.FRIEND_ID)),
                            Duration.ofSeconds(10));
                    waitForFx(() -> listSize(scene, "#membersList") == 2, Duration.ofSeconds(10));

                    begin(() -> {
                        ((ListView<?>) scene.lookup("#membersList")).getSelectionModel().select(1);
                        button(scene, "Remove").fire();
                    });
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(false);
                    assertThat(server.removedMembers()).doesNotContain(FakeChatServer.FRIEND_ID);
                    begin(() -> button(scene, "Remove").fire());
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(true);
                    waitFor(() -> server.removedMembers().contains(FakeChatServer.FRIEND_ID),
                            Duration.ofSeconds(10));

                    begin(() -> button(scene, "Leave group").fire());
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(false);
                    assertThat(server.removedMembers()).doesNotContain(FakeChatServer.USER_ID);
                    begin(() -> button(scene, "Leave group").fire());
                    waitForFx(UserAccountFlowFxTest::confirmVisible, Duration.ofSeconds(10));
                    clickConfirm(true);
                    waitFor(() -> server.removedMembers().contains(FakeChatServer.USER_ID),
                            Duration.ofSeconds(10));
                } finally {
                    onFx(() -> {
                        harness.stage().close();
                        harness.context().close();
                        return true;
                    });
                }
            }
            return null;
        });
    }

    @Test
    void registrationAndPasswordResetScreensSubmit(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            try (FakeChatServer server = new FakeChatServer()) {
                Harness harness = harness(server);
                try {
                    onFx(() -> {
                        RegisterController controller = new RegisterController(harness.context(),
                                username -> { }, () -> { });
                        Parent root = ViewLoader.load(ViewLoader.REGISTER, controller, harness.i18n());
                        Scene scene = new Scene(root, 560, 780);
                        harness.theme().apply(scene);
                        harness.stage().setScene(scene);
                        harness.stage().show();
                        return true;
                    });
                    Scene scene = harness.stage().getScene();
                    assertThat(((TextField) scene.lookup("#usernameField")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((TextField) scene.lookup("#emailField")).getAccessibleText())
                            .isNotBlank();
                    onFx(() -> {
                        fillRegistration(scene, "new_user", "New User", "new@example.test");
                        button(scene, "Create account").fire();
                        return true;
                    });
                    waitForFx(() -> labelContaining(scene, "Account created"), Duration.ofSeconds(10));

                    onFx(() -> {
                        PasswordResetController controller = new PasswordResetController(harness.context(),
                                message -> { }, () -> { });
                        Parent root = ViewLoader.load(ViewLoader.PASSWORD_RESET, controller,
                                harness.i18n());
                        Scene resetScene = new Scene(root, 540, 640);
                        harness.theme().apply(resetScene);
                        harness.stage().setScene(resetScene);
                        return true;
                    });
                    Scene reset = harness.stage().getScene();
                    assertThat(((TextField) reset.lookup("#emailField")).getAccessibleText())
                            .isNotBlank();
                    assertThat(((PasswordField) reset.lookup("#newPasswordField")).getAccessibleText())
                            .isNotBlank();
                    onFx(() -> {
                        field(reset, "you@example.com").setText("alice@example.test");
                        button(reset, "Send reset token").fire();
                        return true;
                    });
                    waitForFx(() -> reset.lookup("#confirmSection").isVisible(), Duration.ofSeconds(10));
                    onFx(() -> {
                        field(reset, "Paste the token from your email").setText("t".repeat(40));
                        ((PasswordField) reset.lookup("#newPasswordField")).setText("reset password 12345");
                        ((PasswordField) reset.lookup("#confirmPasswordField"))
                                .setText("reset password 12345");
                        button(reset, "Save new password").fire();
                        return true;
                    });
                    waitFor(() -> server.passwordResetRequests() == 1, Duration.ofSeconds(10));
                    waitForFx(() -> labelContaining(reset, "Password reset"), Duration.ofSeconds(10));
                } finally {
                    onFx(() -> {
                        harness.stage().close();
                        harness.context().close();
                        return true;
                    });
                }
            }
            return null;
        });
    }

    @Test
    void registrationSignsInAndDuplicateRegistrationIsRejected(@TempDir Path home) throws Exception {
        withHome(home, () -> {
            try (FakeChatServer server = new FakeChatServer()) {
                Stage stage = onFx(Stage::new);
                ThemeManager theme = new ThemeManager();
                theme.setTheme(Theme.LIGHT);
                I18n i18n = new I18n(BASE, java.util.Locale.ENGLISH);
                UserAppContext context = new UserAppContext(stage, server.origin().toString(),
                        false, null, theme, i18n);
                try {
                    onFx(() -> {
                        showRegistration(stage, context, theme);
                        return true;
                    });
                    Scene registration = stage.getScene();
                    onFx(() -> {
                        fillRegistration(registration, "new_user", "New User", "new@example.test");
                        button(registration, "Create account").fire();
                        return true;
                    });
                    waitForFx(() -> labelContaining(registration, "Account created"),
                            Duration.ofSeconds(10));
                    assertThat(server.registrationCount()).isEqualTo(1);

                    AtomicReference<MyProfile> signedIn = new AtomicReference<>();
                    onFx(() -> {
                        LoginController controller = new LoginController(context, signedIn::set);
                        Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, i18n);
                        Scene scene = new Scene(root, 520, 640);
                        theme.apply(scene);
                        stage.setScene(scene);
                        return true;
                    });
                    Scene login = stage.getScene();
                    onFx(() -> {
                        field(login, "Username").setText("new_user");
                        field(login, "Password").setText(PASSWORD);
                        button(login, "Sign in").fire();
                        return true;
                    });
                    waitFor(() -> signedIn.get() != null, Duration.ofSeconds(10));
                    assertThat(signedIn.get().getUsername()).isEqualTo("new_user");
                    assertThat(server.loginUsername()).isEqualTo("new_user");
                    assertThat(server.loginPassword()).isEqualTo(PASSWORD);

                    onFx(() -> {
                        showRegistration(stage, context, theme);
                        return true;
                    });
                    Scene duplicate = stage.getScene();
                    onFx(() -> {
                        fillRegistration(duplicate, "new_user", "New User", "new@example.test");
                        button(duplicate, "Create account").fire();
                        return true;
                    });
                    waitForFx(() -> labelContaining(duplicate, "already in use"),
                            Duration.ofSeconds(10));
                    assertThat(server.registrationCount()).isEqualTo(1);
                } finally {
                    onFx(() -> {
                        stage.close();
                        context.close();
                        return true;
                    });
                }
            }
            return null;
        });
    }

    private static Harness harness(FakeChatServer server) throws Exception {
        Stage stage = onFx(Stage::new);
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        I18n i18n = new I18n(BASE, java.util.Locale.ENGLISH);
        UserAppContext context = new UserAppContext(stage, server.origin().toString(),
                false, null, theme, i18n);
        context.activate(context.signIn("alice", PASSWORD.toCharArray()));
        return new Harness(stage, context, theme, i18n);
    }

    private static int listSize(Scene scene, String id) {
        Node node = scene.lookup(id);
        return node instanceof ListView<?> list ? list.getItems().size() : 0;
    }

    private static String labelText(Scene scene, String id) {
        Node node = scene.lookup(id);
        return node instanceof Label label && label.getText() != null ? label.getText() : "";
    }

    private static void showRegistration(Stage stage, UserAppContext context, ThemeManager theme) {
        RegisterController controller = new RegisterController(context, username -> { }, () -> { });
        Parent root = ViewLoader.load(ViewLoader.REGISTER, controller, context.i18n());
        Scene scene = new Scene(root, 560, 780);
        theme.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static void fillRegistration(Scene scene, String username, String fullName,
                                         String email) {
        field(scene, "Choose a username").setText(username);
        field(scene, "Your full name").setText(fullName);
        field(scene, "you@example.com").setText(email);
        ((DatePicker) scene.lookup("#birthDateField")).setValue(LocalDate.of(1995, 3, 2));
        ((ComboBox<?>) scene.lookup("#genderField")).getSelectionModel().selectFirst();
        ((PasswordField) scene.lookup("#passwordField")).setText(PASSWORD);
        ((PasswordField) scene.lookup("#confirmPasswordField")).setText(PASSWORD);
    }

    private static boolean labelContaining(Scene scene, String text) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(javafx.scene.control.Label.class::isInstance)
                .map(javafx.scene.control.Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()) && candidate.isVisible())
                .findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }

    private static TextField field(Scene scene, String prompt) {
        TextField exact = scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText())).findFirst().orElse(null);
        if (exact != null) return exact;
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(field -> field.getPromptText() != null && field.getPromptText().contains(prompt))
                .findFirst().orElseThrow(() -> new AssertionError("Missing field: " + prompt));
    }

    private static boolean confirmVisible() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().lookup("#confirmDialogConfirm") != null) return true;
        }
        return false;
    }

    private static void clickConfirm(boolean confirm) {
        onFx(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null) {
                    Button button = (Button) window.getScene().lookup(confirm
                            ? "#confirmDialogConfirm" : "#confirmDialogCancel");
                    if (button != null) {
                        button.fire();
                        return true;
                    }
                }
            }
            throw new AssertionError("Confirmation dialog is not visible.");
        });
    }

    private static void begin(Runnable action) {
        Platform.runLater(action);
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep();
        }
        throw new AssertionError("Timed out waiting for the fake server.");
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The scene can still be swapping.
            }
            sleep();
        }
        throw new AssertionError("Timed out waiting for the JavaFX UI.");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting.", exception);
        }
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

    private static void withHome(Path home, Callable<Void> action) throws Exception {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            action.call();
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
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
                throw new IllegalStateException("JavaFX toolkit did not start.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }

    private record Harness(Stage stage, UserAppContext context, ThemeManager theme, I18n i18n) {
    }
}
