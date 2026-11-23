package com.nexus.desktop.admin;

import com.nexus.sdk.model.AdminUser;
import com.nexus.sdk.model.MyProfile;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyboard operation of the administrator client: the sign-in form and the workspace navigation,
 * which is the screen an operator lives in.
 *
 * <p>Only behaviour measured on JavaFX 27 is asserted. A probe of this screen showed the login
 * scene leaving the focus owner {@code null} until the user tabs or clicks, so there is no
 * "the form starts in the username field" check here; the Enter paths and the shell's arrow-key
 * navigation are the ones that exist and can fail.</p>
 */
class AdminKeyboardFxTest {

    @Test
    void enterInTheUsernameMovesFocusToThePasswordField() {
        try (AdminSessions.Session session = AdminSessions.open("http://127.0.0.1:1")) {
            Parent root = session.show(AdminViewLoader.LOGIN,
                    () -> new AdminLoginController(session.context(), profile -> { }), 520, 640);
            TextField username = (TextField) root.lookup("#usernameField");
            PasswordField password = (PasswordField) root.lookup("#passwordField");

            onFx(() -> {
                username.requestFocus();
                username.setText("admin");
                press(username, KeyCode.ENTER);
                return true;
            });

            assertThat(onFx(() -> session.scene().getFocusOwner()))
                    .as("Enter on the username field moves on to the password")
                    .isSameAs(password);
        }
    }

    @Test
    void enterInThePasswordSignsIn() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             AdminSessions.Session session = AdminSessions.signedIn(server)) {
            AtomicReference<MyProfile> signedIn = new AtomicReference<>();
            Parent root = session.show(AdminViewLoader.LOGIN,
                    () -> new AdminLoginController(session.context(), signedIn::set), 520, 640);
            PasswordField password = (PasswordField) root.lookup("#passwordField");

            onFx(() -> {
                ((TextField) root.lookup("#usernameField")).setText("admin");
                password.requestFocus();
                password.setText("correct horse battery staple");
                press(password, KeyCode.ENTER);
                return true;
            });

            waitForFx(() -> signedIn.get() != null, Duration.ofSeconds(10));
            assertThat(signedIn.get().getUsername()).as("the signed-in profile").isEqualTo("admin");
        }
    }

    @Test
    void arrowKeysMoveTheShellNavigationAndSwapTheWorkspace() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             AdminSessions.Session session = AdminSessions.signedIn(server)) {
            Parent shell = session.show(AdminViewLoader.SHELL,
                    () -> new AdminShellController(session.context(), () -> { }), 1280, 720);
            ListView<?> navigation = (ListView<?>) shell.lookup("#navigationList");

            onFx(() -> {
                navigation.requestFocus();
                press(navigation, KeyCode.DOWN);
                return true;
            });

            assertThat(onFx(() -> navigation.getSelectionModel().getSelectedIndex()))
                    .as("the arrow moved the workspace").isEqualTo(1);
            // The content host is what the operator reads; the selection alone would be a label.
            waitForFx(() -> {
                Node content = content(shell);
                return content != null && content.lookup("#reportsTable") != null;
            }, Duration.ofSeconds(10));
        }
    }

    @Test
    void arrowKeysInTheAccountsTableFillTheDetailPane() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             AdminSessions.Session session = AdminSessions.signedIn(server)) {
            Parent root = session.show(AdminViewLoader.ACCOUNTS,
                    () -> new AccountsController(session.context()), 1280, 720);
            TableView<?> accounts = table(root, "#accountsTable");
            waitForFx(() -> accounts.getItems().size() >= 2, Duration.ofSeconds(20));
            TextField detailUsername = (TextField) root.lookup("#usernameField");
            int selected = onFx(() -> accounts.getSelectionModel().getSelectedIndex());

            onFx(() -> {
                accounts.requestFocus();
                press(accounts, KeyCode.DOWN);
                return true;
            });

            assertThat(onFx(() -> accounts.getSelectionModel().getSelectedIndex()))
                    .as("the arrow moved the selection on").isEqualTo(selected + 1);
            // The detail pane is what the operator works in; a selection alone would be a colour.
            assertThat(onFx(() -> detailUsername.getText()))
                    .as("the detail pane follows the keyboard selection")
                    .isEqualTo(onFx(() -> ((AdminUser) accounts.getSelectionModel()
                            .getSelectedItem()).getUsername()));
        }
    }

    @Test
    void aRowDecisionOpensItsConfirmationAndCancelReturnsFocus() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             AdminSessions.Session session = AdminSessions.signedIn(server)) {
            Parent root = session.show(AdminViewLoader.REPORTS,
                    () -> new ReportsController(session.context()), 1280, 720);
            TableView<?> reports = table(root, "#reportsTable");
            waitForFx(() -> !reports.getItems().isEmpty(), Duration.ofSeconds(20));
            // The row cells are built during a layout pass, which the data arrives after.
            onFx(() -> {
                reports.applyCss();
                reports.layout();
                return true;
            });
            Button uphold = rowButton(reports, ".danger-button");
            Node confirm = root.lookup("#confirmDecisionButton");
            Node cancel = root.lookup("#cancelDecisionButton");
            Node box = root.lookup("#decisionConfirmBox");

            onFx(() -> {
                uphold.requestFocus();
                press(uphold, KeyCode.SPACE);
                return true;
            });

            assertThat(onFx(box::isVisible)).as("Space opened the confirmation").isTrue();
            // The controller focuses the button the operator just chose, so a second Space would
            // submit rather than silently do nothing; the check follows what the screen does.
            assertThat(onFx(() -> session.scene().getFocusOwner()))
                    .as("the confirmation takes focus").isSameAs(confirm);

            onFx(() -> {
                press(cancel, KeyCode.SPACE);
                return true;
            });

            assertThat(onFx(box::isVisible)).as("cancel closes the confirmation").isFalse();
            assertThat(onFx(() -> session.scene().getFocusOwner()))
                    .as("cancel gives focus back to the row action").isSameAs(uphold);
        }
    }

    private static TableView<?> table(Parent root, String id) {
        TableView<?> table = (TableView<?>) root.lookup(id);
        assertThat(table).as("the %s table", id).isNotNull();
        return table;
    }

    /** The first realised row's action button, so the test drives a real row and not a prototype. */
    private static Button rowButton(TableView<?> table, String selector) {
        for (Node node : table.lookupAll(selector)) {
            if (node instanceof Button button && button.getScene() != null) return button;
        }
        throw new AssertionError("No realised row builds a " + selector);
    }

    private static Node content(Parent shell) {
        StackPane host = (StackPane) shell.lookup("#contentHost");
        return host == null || host.getChildren().isEmpty() ? null : host.getChildren().getFirst();
    }

    /**
     * A real keystroke: the toolkit delivers key input to the focus owner, so the event is aimed at
     * the control rather than at the scene.
     */
    private static void press(Node target, KeyCode code) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false));
        target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code,
                false, false, false, false));
    }
}
