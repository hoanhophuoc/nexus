package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;

class AdminDesignSystemFxTest {
    private static final String BASE = "com.nexus.desktop.admin.i18n.messages";

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
                assertThat(labels(root)).contains("Đăng nhập quản trị", "Quản trị Nexus");
                assertThat(scene.getRoot().lookupAll(".label").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .map(Label::getText).filter(text -> text != null)
                        .noneMatch(text -> text.contains("admin."))).isTrue();

                // Looked up by their Vietnamese prompts, so a key that falls back to English
                // instead of a translation fails here rather than passing as translated.
                TextField username = field(scene, "Tên đăng nhập quản trị");
                PasswordField password = (PasswordField) field(scene, "Mật khẩu");
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
                        .allMatch(AdminDesignSystemFxTest::named)).isTrue();

                // The theme toggle is an icon with Vietnamese accessible text, not a glyph in the
                // bundle; the click proves the graphic and the wording follow the theme.
                Button themeToggle = (Button) root.lookup("#themeButton");
                assertThat(themeToggle.getGraphic()).isInstanceOf(FontIcon.class);
                assertThat(((FontIcon) themeToggle.getGraphic()).getIconCode())
                        .as("a light view offers the moon").isEqualTo(Feather.MOON);
                assertThat(themeToggle.getAccessibleText())
                        .isEqualTo(vietnamese.get("admin.theme.toDark"));
                assertThat(themeToggle.getTooltip()).as("an icon-only button explains itself on hover")
                        .isNotNull();
                assertThat(themeToggle.getTooltip().getText())
                        .isEqualTo(vietnamese.get("admin.theme.toDark"));

                Paint light = signIn.getTextFill();
                themeToggle.fire();
                root.applyCss();
                assertThat(root.getStyleClass()).contains("theme-dark");
                assertThat(((FontIcon) themeToggle.getGraphic()).getIconCode())
                        .as("the dark view offers the sun").isEqualTo(Feather.SUN);
                assertThat(themeToggle.getAccessibleText())
                        .isEqualTo(vietnamese.get("admin.theme.toLight"));
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
            // The sign-in is three blocking HTTP calls; keeping it off the FX thread is what stops a
            // loaded machine from turning it into a stalled 30 second FX task.
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<ThemeManager> themeReference = new AtomicReference<>();
            AtomicReference<I18n> viReference = new AtomicReference<>();
            AdminAppContext signInContext = onFx(() -> {
                Stage stage = new Stage();
                stageReference.set(stage);
                ThemeManager theme = new ThemeManager();
                theme.setTheme(Theme.LIGHT);
                themeReference.set(theme);
                I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));
                viReference.set(vietnamese);
                return new AdminAppContext(stage, server.origin().toString(), theme, vietnamese);
            });
            var signedIn = signInContext.signIn("admin", "correct horse battery staple".toCharArray());
            assertThat(onFx(() -> {
                Stage stage = stageReference.get();
                ThemeManager theme = themeReference.get();
                I18n vietnamese = viReference.get();
                AdminAppContext context = signInContext;
                context.activate(signedIn);
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
                return true;
            })).isTrue();

            // A second, shorter FX task: each screen gets its own window, so one slow load cannot
            // consume the budget of the whole test on a loaded machine.
            I18n english = new I18n(BASE, Locale.ENGLISH);
            assertThat(onFx(() -> {
                Stage stage = stageReference.get();
                ThemeManager theme = themeReference.get();
                I18n vietnamese = viReference.get();
                AdminAppContext context = signInContext;
                try {
                    ResourceBundle vi = vietnamese.bundle();
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

    /**
     * A cell the column cannot fit explains its whole value on hover, and only then. The shell shows
     * the accounts table in a narrower content area than the workspace does, which is where a full
     * name or an email address stops fitting, so the narrow mount is the real case and the wide one
     * proves the explanation goes away when the value fits again.
     */
    @Test
    void accountCellsExplainValuesOnlyWhenTheyAreTruncated() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer()) {
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<ThemeManager> themeReference = new AtomicReference<>();
            AdminAppContext context = onFx(() -> {
                Stage stage = new Stage();
                stageReference.set(stage);
                ThemeManager theme = new ThemeManager();
                theme.setTheme(Theme.LIGHT);
                themeReference.set(theme);
                return new AdminAppContext(stage, server.origin().toString(), theme,
                        new I18n(BASE, Locale.ENGLISH));
            });
            // Three blocking HTTP calls, kept off the FX thread like the other admin suites.
            var signedIn = context.signIn("admin", "correct horse battery staple".toCharArray());
            Stage stage = stageReference.get();
            try {
                Parent accounts = onFx(() -> {
                    context.activate(signedIn);
                    Parent root = AdminViewLoader.load(AdminViewLoader.ACCOUNTS,
                            new AccountsController(context), new I18n(BASE, Locale.ENGLISH));
                    Scene scene = new Scene(root, 640, 620);
                    themeReference.get().apply(scene);
                    AdminViewLoader.applyStylesheet(scene);
                    stage.setScene(scene);
                    stage.show();
                    root.applyCss();
                    root.layout();
                    return root;
                });
                waitForFx(() -> accountsRows(accounts) > 0
                        && tableRows(accounts, "#historyTable") > 0
                        && tableRows(accounts, "#friendsTable") > 0, Duration.ofSeconds(10));
                onFx(() -> {
                    accounts.applyCss();
                    accounts.layout();
                    return true;
                });

                TableCell<?, ?> email = cellWithText(accounts, "#accountsTable", "mallory@example.test");
                assertThat(email).as("the reported account's email cell is rendered").isNotNull();
                assertThat(email.getTooltip()).as("a value the column cannot fit explains itself")
                        .isNotNull();
                assertThat(email.getTooltip().getText())
                        .as("the tooltip carries the whole value, not the clipped prefix")
                        .isEqualTo("mallory@example.test");
                assertThat(email.getAccessibleText())
                        .as("assistive technology reads the whole value too")
                        .isEqualTo("mallory@example.test");

                TableCell<?, ?> shortValue = cellWithText(accounts, "#accountsTable", "alice");
                assertThat(shortValue).as("a short username cell is rendered").isNotNull();
                assertThat(shortValue.getTooltip())
                        .as("a value that fits stays a plain cell").isNull();
                assertThat(shortValue.getAccessibleText())
                        .as("a value that fits needs no extra accessible text").isNull();

                // The login history pane sits in the same narrow detail column, where the combined
                // full name and username stops fitting for the same reason.
                TableCell<?, ?> historyUser = cellWithText(accounts, "#historyTable",
                        "Alice Example (alice)");
                assertThat(historyUser).as("the selected account's login history is rendered")
                        .isNotNull();
                assertThat(historyUser.getTooltip())
                        .as("a login history value the pane cannot fit explains itself").isNotNull();
                assertThat(historyUser.getTooltip().getText())
                        .as("the whole value, not the clipped prefix")
                        .isEqualTo("Alice Example (alice)");
                assertThat(historyUser.getAccessibleText())
                        .as("assistive technology reads the whole value there too")
                        .isEqualTo("Alice Example (alice)");

                // The friend list shares that pane: the seeded friend is wide enough to be clipped
                // in the three narrow columns, so the whole name has to stay reachable.
                TableCell<?, ?> friendName = cellWithText(accounts, "#friendsTable",
                        FakeAdminServer.FRIEND_FULL_NAME);
                assertThat(friendName).as("the selected account's friend is rendered").isNotNull();
                assertThat(friendName.getTooltip()).as("a friend name the pane cannot fit explains itself")
                        .isNotNull();
                assertThat(friendName.getTooltip().getText())
                        .as("the whole name, not the clipped prefix")
                        .isEqualTo(FakeAdminServer.FRIEND_FULL_NAME);
                assertThat(friendName.getAccessibleText())
                        .as("assistive technology reads the whole name there too")
                        .isEqualTo(FakeAdminServer.FRIEND_FULL_NAME);

                // The same cell, in a window wide enough for the value: the explanation must go
                // away rather than staying installed on a reused cell.
                onFx(() -> {
                    stage.setWidth(1600);
                    accounts.applyCss();
                    accounts.layout();
                    return true;
                });
                // The scene follows the window on the next pulse, so the widened layout is polled
                // rather than assumed; the message names what was actually still on screen.
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                String state = "unread";
                while (System.nanoTime() < deadline) {
                    state = onFx(() -> {
                        TableView<?> table = (TableView<?>) accounts.lookup("#accountsTable");
                        TableCell<?, ?> cell = cellWithText(accounts, "#accountsTable",
                                "mallory@example.test");
                        return "table " + table.getWidth() + " scene " + accounts.getScene().getWidth()
                                + " cell " + (cell == null ? "none" : cell.getWidth())
                                + " tooltip " + (cell == null || cell.getTooltip() == null ? "none"
                                : cell.getTooltip().getText());
                    });
                    boolean done = Boolean.TRUE.equals(onFx(() -> {
                        TableCell<?, ?> cell = cellWithText(accounts, "#accountsTable",
                                "mallory@example.test");
                        return cell != null && cell.getTooltip() == null;
                    }));
                    if (done) break;
                    Thread.sleep(50);
                }
                TableCell<?, ?> wide = cellWithText(accounts, "#accountsTable", "mallory@example.test");
                assertThat(wide).as("the email cell survives the resize: %s", state).isNotNull();
                assertThat(wide.getTooltip()).as("a value that now fits has no tooltip: %s", state)
                        .isNull();
                assertThat(wide.getAccessibleText()).isNull();
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        }
    }

    /**
     * The audit log has the same problem in the same place: the shell mounts it in a content area
     * narrower than the workspace's, and an actor ID is the value that stops fitting.
     */
    @Test
    void auditCellsExplainValuesOnlyWhenTheyAreTruncated() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer()) {
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<ThemeManager> themeReference = new AtomicReference<>();
            AdminAppContext context = onFx(() -> {
                Stage stage = new Stage();
                stageReference.set(stage);
                ThemeManager theme = new ThemeManager();
                theme.setTheme(Theme.LIGHT);
                themeReference.set(theme);
                return new AdminAppContext(stage, server.origin().toString(), theme,
                        new I18n(BASE, Locale.ENGLISH));
            });
            var signedIn = context.signIn("admin", "correct horse battery staple".toCharArray());
            Stage stage = stageReference.get();
            try {
                Parent audit = onFx(() -> {
                    context.activate(signedIn);
                    Parent root = AdminViewLoader.load(AdminViewLoader.AUDIT,
                            new AuditController(context), new I18n(BASE, Locale.ENGLISH));
                    Scene scene = new Scene(root, 640, 620);
                    themeReference.get().apply(scene);
                    AdminViewLoader.applyStylesheet(scene);
                    stage.setScene(scene);
                    stage.show();
                    root.applyCss();
                    root.layout();
                    return root;
                });
                waitForFx(() -> tableRows(audit, "#auditTable") > 0, Duration.ofSeconds(10));
                onFx(() -> {
                    audit.applyCss();
                    audit.layout();
                    return true;
                });

                String actor = FakeAdminServer.ADMIN_ID.toString();
                TableCell<?, ?> actorCell = cellWithText(audit, "#auditTable", actor);
                assertThat(actorCell).as("the audit event's actor cell is rendered").isNotNull();
                assertThat(actorCell.getTooltip())
                        .as("an actor ID the narrow pane cannot fit explains itself").isNotNull();
                assertThat(actorCell.getTooltip().getText())
                        .as("the whole ID, not the clipped prefix").isEqualTo(actor);
                assertThat(actorCell.getAccessibleText())
                        .as("assistive technology reads the whole ID too").isEqualTo(actor);

                TableCell<?, ?> actionCell = cellWithText(audit, "#auditTable", "admin.user.lock");
                assertThat(actionCell).as("the audit event's action cell is rendered").isNotNull();
                assertThat(actionCell.getTooltip())
                        .as("an action that fits stays a plain cell").isNull();
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        }
    }

    private static int accountsRows(Parent root) {
        return tableRows(root, "#accountsTable");
    }

    private static int tableRows(Parent root, String tableId) {
        if (!(root.lookup(tableId) instanceof TableView<?> table)) return 0;
        return table.getItems().size();
    }

    /** The rendered cell showing exactly {@code text}; its text stays whole even when it is clipped. */
    private static TableCell<?, ?> cellWithText(Parent root, String tableId, String text) {
        if (!(root.lookup(tableId) instanceof TableView<?> table)) return null;
        for (javafx.scene.Node node : table.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell && !cell.isEmpty() && text.equals(cell.getText())) {
                return cell;
            }
        }
        return null;
    }

    /** A button either says what it does or shows an icon that does, with accessible text. */
    private static boolean named(Button button) {
        if (button.getText() != null && !button.getText().isBlank()) return true;
        return button.getGraphic() instanceof FontIcon
                && button.getAccessibleText() != null
                && !button.getAccessibleText().isBlank();
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

}
