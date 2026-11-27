package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;

class AdminDesignSystemFxTest {
    private static final String BASE = "com.nexus.desktop.admin.i18n.messages";
    private static final I18n ENGLISH = new I18n(BASE, Locale.ENGLISH);
    /** The width the shell gives a workspace; narrower than any screen mounted on its own. */
    private static final int NARROW_WIDTH = 640;
    private static final int NARROW_HEIGHT = 620;

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
        try (FakeAdminServer server = new FakeAdminServer();
             Mounted mounted = mountedScreen(server, AdminViewLoader.ACCOUNTS,
                     AccountsController::new, NARROW_WIDTH, NARROW_HEIGHT)) {
            Parent accounts = mounted.root();
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
                mounted.stage().setWidth(1600);
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
        }
    }

    /**
     * The audit log has the same problem in the same place: the shell mounts it in a content area
     * narrower than the workspace's, and an actor ID is the value that stops fitting.
     */
    @Test
    void auditCellsExplainValuesOnlyWhenTheyAreTruncated() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             Mounted mounted = mountedScreen(server, AdminViewLoader.AUDIT,
                     AuditController::new, NARROW_WIDTH, NARROW_HEIGHT)) {
            Parent audit = afterFirstRows(mounted.root(), "#auditTable");

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
        }
    }

    /**
     * The cap was added for values that run past the window, and an address or a path that never
     * offers a space is exactly that: inside the 420 px width JavaFX wraps the token at its own
     * glyph boundaries, so the popup bounds it — while the hover text, the value the screen reader
     * hears and the value the copy shortcut takes stay the untouched value.
     */
    @Test
    void anUnbrokenTokenStaysInsideTheTooltipWidth() throws Exception {
        String token = "very.long.unbroken.mailbox.segment.".repeat(5) + "@subdomain.example.test";
        assertThat(token).as("the sample has no space to break at").doesNotContain(" ");
        assertThat(token.length()).as("the sample is far too wide for one line in the popup")
                .isGreaterThan(AdminUi.TOOLTIP_PREVIEW_CHARS / 2);

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<AdminUi.RowInspector> inspectorReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Email");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            inspectorReference.set(AdminUi.keyboardReadable(loaded));
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 220, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            loaded.requestFocus();
            loaded.getSelectionModel().selectFirst();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the unbroken value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText())
                    .as("the popup carries the value itself, with nothing added to it")
                    .isEqualTo(token);
            assertThat(cell.getAccessibleText())
                    .as("the accessible text is the same value")
                    .isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup wraps inside its width: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(size[1]).as("and stays inside the window: %s px", size[1])
                    .isLessThan(200);

            onFx(() -> {
                table.fireEvent(shortcut(KeyCode.C));
                return true;
            });
            assertThat(onFx(() -> Clipboard.getSystemClipboard().getString()))
                    .as("the copied value is the same value")
                    .isEqualTo(token);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * A value written with a combining mark is the one the cap used to lose: JavaFX sizes a tooltip
     * carrying one from the text's unwrapped width, so the popup grew to 540 px and painted outside
     * its own box. The hover copy is composed to NFC before it is measured, which keeps the same
     * glyphs and lets the popup wrap at 420 px again — while the cell keeps the value as submitted
     * for the accessible text and the copy.
     */
    @Test
    void aValueWithACombiningMarkStaysInsideTheTooltipWidth() throws Exception {
        String accented = "cafe\u0301";
        String emoji = "\u2764\uFE0F";
        String token = "Rehabilitation-Wing-Suite-314-Flat-" + accented + emoji
                + "Building3Storey2ActiveResidentDesk314159";
        assertThat(token).as("the sample writes the accent as a combining mark")
                .contains("\u0301");
        assertThat(token).as("the sample has no space to break at").doesNotContain(" ");
        String composed = Normalizer.normalize(token, Normalizer.Form.NFC);

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 240, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText()).as("the hover copy carries the composed letter")
                    .isEqualTo(composed).doesNotContain("\u0301");
            assertThat(cell.getAccessibleText())
                    .as("assistive technology reads the value as submitted")
                    .isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup wraps inside its width: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(size[1]).as("and stays inside the window: %s px", size[1])
                    .isLessThan(200);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * A mark with no precomposed form is what composing cannot fix: NFC leaves it in place, and
     * JavaFX then refuses to wrap the tooltip at all, sizing the popup from the unwrapped value.
     * The hover copy is broken into lines by hand instead — with every code point intact — while the
     * cell keeps the value as submitted for the accessible text and the copy.
     */
    @Test
    void aValueWhoseMarkHasNoPrecomposedFormStaysInsideTheTooltipWidth() throws Exception {
        String macron = "x\u0304";
        String vowelSign = "\u0915\u093F";
        String token = "Rehabilitation-Wing-Suite-314-Flat-cafe" + macron
                + "Building3Storey2ActiveResidentDesk314159" + vowelSign + "ActiveResidentDesk314159";
        assertThat(Normalizer.normalize(token, Normalizer.Form.NFC))
                .as("the sample carries a mark composing cannot remove").isEqualTo(token);
        assertThat(token).as("the sample has no space to break at").doesNotContain(" ");

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 240, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText()).as("the hover copy is broken into lines by hand")
                    .contains("\n");
            assertThat(hover.getText().replace("\n", ""))
                    .as("every code point survives in order").isEqualTo(token);
            assertThat(cell.getAccessibleText())
                    .as("assistive technology reads the value as submitted")
                    .isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup wraps inside its width: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(size[1]).as("and stays inside the window: %s px", size[1])
                    .isLessThan(200);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The hand breaking is only for a copy the tooltip cannot fit: a short value carrying the same
     * kind of uncomposable mark is left exactly as it is, on one line, with no newline added.
     */
    @Test
    void aShortValueWithAMarkStaysOnOneUnbrokenLine() throws Exception {
        String token = "Ticket-3145-cafe" + "x\u0304" + "Building3";
        assertThat(Normalizer.normalize(token, Normalizer.Form.NFC))
                .as("the sample carries a mark composing cannot remove").isEqualTo(token);

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            // Narrow enough that this short value is still clipped by the cell, so it explains itself.
            Scene scene = new Scene(loaded, 120, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText()).as("a copy that fits is not broken")
                    .isEqualTo(token).doesNotContain("\n");
            assertThat(cell.getAccessibleText())
                    .as("assistive technology reads the value as submitted")
                    .isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup holds the copy: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * A value too wide for the tooltip that does offer spaces breaks at them rather than through the
     * middle of a word, and rejoining the lines rebuilds the value exactly.
     */
    @Test
    void aValueTooWideForTheTooltipBreaksAtASpace() throws Exception {
        String token = "Rehabilitation Wing Suite 314 Flat cafe" + "x\u0304"
                + " with a long body of text that has to wrap somewhere sensible inside the popup";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 200, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText()).as("the copy is broken into lines").contains("\n");
            assertThat(hover.getText().replace("\n", " "))
                    .as("the lines are the value, broken where its spaces were").isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup holds the copy: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The width the copy is measured against is the tooltip's own, not the cell's. A table styled
     * larger than the theme still explains itself in the theme's font — the popup's label carries
     * the theme class, which sets the size — so this value fits the tooltip even though it would be
     * too wide in the cell's larger font, and must not be broken.
     */
    @Test
    void theCopyIsMeasuredWithTheTooltipsFontNotTheCells() throws Exception {
        String token = "Rehabilitation-Wing-Suite-314-Flat-cafe" + "x\u0304"
                + "Building3Storey2";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            // Only the cell is bigger; the tooltip keeps the theme's size.
            loaded.setStyle("-fx-font-size: 18px;");
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 240, 160);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            assertThat(cell.getFont().getSize())
                    .as("the two fonts really differ: the cell draws this larger")
                    .isGreaterThan(13.5);
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText())
                    .as("measured with the tooltip's font, this copy still fits on one line")
                    .isEqualTo(token).doesNotContain("\n");

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup holds the copy: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The marked copies in the dark theme. Its theme class carries the same font as the light one, so
     * the popup paints at 13 px here too: the long value is broken, the short one keeps its single
     * line, and both popups stay inside the width.
     */
    @Test
    void aMarkedCopyStaysInsideTheTooltipWidthInTheDarkTheme() throws Exception {
        String longToken = "Rehabilitation-Wing-Suite-314-Flat-cafe" + "x\u0304"
                + "Building3Storey2ActiveResidentDesk314159" + "\u0915\u093F" + "ActiveResidentDesk314159";
        String shortToken = "Ticket-3145-cafe" + "x\u0304" + "Building3";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.DARK);
            TableView<String> loaded = new TableView<>(
                    FXCollections.observableArrayList(longToken, shortToken));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            // Narrow enough that both values are clipped by their cells, so each explains itself.
            Scene scene = new Scene(loaded, 120, 160);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> longCell = onFx(() -> cellWithText(table, longToken));
            assertThat(longCell).as("the long value is rendered and clipped").isNotNull();
            Tooltip longHover = longCell.getTooltip();
            assertThat(longHover).as("a clipped value explains itself").isNotNull();
            assertThat(longHover.getText()).as("the long copy is broken into lines").contains("\n");
            assertThat(longHover.getText().replace("\n", ""))
                    .as("every code point survives in order").isEqualTo(longToken);
            double[] longSize = shownSize(longHover, longCell);
            assertThat(longSize[0]).as("the long popup wraps inside its width: %s px", longSize[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(longSize[1]).as("and stays inside the window: %s px", longSize[1])
                    .isLessThan(200);
            assertThat(shownFontSize(longHover, longCell))
                    .as("the dark popup paints at the theme's size").isEqualTo(13.0);

            TableCell<?, ?> shortCell = onFx(() -> cellWithText(table, shortToken));
            assertThat(shortCell).as("the short value is rendered and clipped").isNotNull();
            Tooltip shortHover = shortCell.getTooltip();
            assertThat(shortHover).as("a clipped value explains itself").isNotNull();
            assertThat(shortHover.getText()).as("a copy that fits is not broken")
                    .isEqualTo(shortToken).doesNotContain("\n");
            double[] shortSize = shownSize(shortHover, shortCell);
            assertThat(shortSize[0]).as("the short popup holds the copy: %s px", shortSize[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * A run with no space in it may still carry sequences a reader sees as one character — a joined
     * family, a flag. Nothing is added to such a value: inside the 420 px width JavaFX wraps the run
     * at its own glyph boundaries, so the popup bounds it and both sequences stay in the text the
     * screen reader hears. The combining-mark case has its own test.
     */
    @Test
    void aTokenWithJoinedSequencesStaysInsideTheTooltipWidth() throws Exception {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66";
        String flag = "\uD83C\uDDFB\uD83C\uDDF3";
        String token = "a".repeat(36) + family + flag + "@example.test";
        assertThat(token).as("the sample has no space to break at").doesNotContain(" ");

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 200, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            assertThat(hover.getText())
                    .as("the popup carries the value itself, with nothing added to it")
                    .isEqualTo(token);
            assertThat(hover.getText()).as("both joined sequences survive intact")
                    .contains(family).contains(flag);
            assertThat(cell.getAccessibleText())
                    .as("assistive technology reads the same value")
                    .isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup wraps inside its width: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(size[1]).as("and stays inside the window: %s px", size[1])
                    .isLessThan(200);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The hand breaking must never cut inside a cluster a reader sees as one character. This copy
     * activates it — a macron on an "x" that NFC cannot compose — and also carries a joined family
     * and a flag, so every line has to end on an extended grapheme boundary: a break inside either
     * sequence would paint a lone joiner or half a flag, and the copy would no longer be the value.
     * The cell keeps the value as submitted for the accessible text and the copy.
     */
    @Test
    void aMarkedCopyNeverBreaksInsideAJoinedSequence() throws Exception {
        String macron = "x\u0304";
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        String flag = "\uD83C\uDDFB\uD83C\uDDF3";
        // Enough repeats that the copy spans several lines and the breaks really have to fall
        // among these sequences rather than around them; the copy stays under the preview cap, so
        // nothing is shortened before it is broken and the value under test is the whole value.
        String token = "Rehabilitation-Wing-Suite-314-Flat-cafe" + macron
                + (family + flag).repeat(6)
                + "Building3Storey2ActiveResidentDesk314159";
        assertThat(Normalizer.normalize(token, Normalizer.Form.NFC))
                .as("the sample carries a mark composing cannot remove").isEqualTo(token);
        assertThat(token).as("the sample has no space to break at").doesNotContain(" ");

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(token));
            TableColumn<String, String> column = new TableColumn<>("Contact");
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 240, 140);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            return loaded;
        });
        try {
            TableCell<?, ?> cell = onFx(() -> cellWithText(table, token));
            assertThat(cell).as("the value is rendered and clipped").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself").isNotNull();
            String copy = hover.getText();
            assertThat(copy).as("the copy is broken into lines by hand").contains("\n");
            assertThat(copy.replace("\n", ""))
                    .as("no code point is dropped or reordered by the break").isEqualTo(token);
            assertThat(copy).as("the joined family was not split by a line break")
                    .contains(family).contains(flag);
            String unbroken = copy.replace("\n", "");
            int offset = 0;
            for (String line : copy.split("\n", -1)) {
                final int boundary = offset;
                assertThat(isClusterBoundary(unbroken, boundary))
                        .as("line at %d starts on a grapheme boundary, never inside a joined "
                                + "sequence: %s", boundary, copy)
                        .isTrue();
                // The newline itself is not part of the unbroken copy, so only the line's own
                // length moves the offset of the next one.
                offset += line.length();
            }
            assertThat(copy.lines()).as("no line starts inside a cluster: %s", copy)
                    .allMatch(line -> !line.isEmpty()
                            && line.charAt(0) != '\u200D'
                            && Character.getType(line.codePointAt(0)) != Character.NON_SPACING_MARK
                            && Character.getType(line.codePointAt(0)) != Character.ENCLOSING_MARK
                            && Character.getType(line.codePointAt(0)) != Character.COMBINING_SPACING_MARK
                            && !Character.isLowSurrogate(line.charAt(0)));
            assertThat(cell.getAccessibleText())
                    .as("assistive technology reads the value as submitted")
                    .isEqualTo(token);

            double[] size = shownSize(hover, cell);
            assertThat(size[0]).as("the popup wraps inside its width: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(size[1]).as("and stays inside the window: %s px", size[1])
                    .isLessThan(200);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * A value that is a paragraph — a submitted message, a long report reason — cannot be shown in
     * a hover without making the tooltip taller than the window, so the hover keeps its opening
     * words while the cell keeps the whole value as accessible text. The table here is built from
     * the production helpers, so the cell under test is the one every admin screen installs.
     */
    @Test
    void aVeryLongExplanationKeepsItsHoverShort() throws Exception {
        String longMessage = String.join(" ",
                "The reported account sent a long series of direct messages after being asked",
                "to stop, and the reporter submitted the whole conversation as evidence for",
                "this case, including the times and the replies that followed each message.",
                "The messages continued the next day, and the reporter asked twice more for",
                "them to stop before reporting the account to the moderation team, which",
                "asked for the conversation, the timestamps and any screenshots as evidence.");
        assertThat(longMessage.length()).isGreaterThan(AdminUi.TOOLTIP_PREVIEW_CHARS * 2);

        AtomicReference<Stage> stageReference = new AtomicReference<>();
        TableCell<?, ?> cell = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> table = new TableView<>(
                    FXCollections.observableArrayList(longMessage, "short"));
            TableColumn<String, String> column = new TableColumn<>();
            AdminUi.bind(column, value -> value);
            AdminUi.explainTruncatedText(column, ENGLISH);
            table.getColumns().add(column);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(table, 280, 200);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            table.applyCss();
            table.layout();
            return cellWithText(table, longMessage);
        });
        try {
            assertThat(cell).as("the long value is rendered in a column that clips it").isNotNull();
            Tooltip hover = cell.getTooltip();
            assertThat(hover).as("a clipped value explains itself on hover").isNotNull();
            assertThat(hover.getText().length())
                    .as("the hover keeps only the opening words")
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_PREVIEW_CHARS + 1);
            assertThat(hover.getText()).as("a shortened hover says so").endsWith("…");
            assertThat(longMessage).startsWith(hover.getText().substring(0, 40));
            assertThat(cell.getAccessibleText())
                    .as("assistive technology still reads the whole value")
                    .isEqualTo(longMessage);

            // A hover of 201 characters cannot be one line inside 420 px, so measuring the shown
            // popup proves the wrapping and the height cap together: wide enough to read, never
            // taller than the window.
            double[] size = shownSize(hover, cell);
            assertThat(hover.isWrapText()).as("the explanation wraps").isTrue();
            assertThat(size[0]).as("the hover stays inside its readable width: %s px", size[0])
                    .isLessThanOrEqualTo(AdminUi.TOOLTIP_WRAP_WIDTH + 1);
            assertThat(size[1]).as("and inside the window's height: %s px", size[1])
                    .isLessThan(200);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The keyboard lives on rows, not cells: a JavaFX table keeps the focus model on the selected
     * row, so that is where a clipped value has to become readable. F2 opens the row in full and
     * the copy shortcut takes it away — the same values the hover explains, one cell at a time.
     */
    @Test
    void aKeyboardUserCanReadAndCopyTheSelectedRow() throws Exception {
        String value = "Mallory Q. Reported-Example, 3 Report Lane, Apartment 14";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<AdminUi.RowInspector> inspectorReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(
                    FXCollections.observableArrayList(value, "short"));
            TableColumn<String, String> column = new TableColumn<>("Address");
            AdminUi.bind(column, cell -> cell);
            AdminUi.explainTruncatedText(column, ENGLISH);
            AdminUi.wrapHeader(column);
            loaded.getColumns().add(column);
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            inspectorReference.set(AdminUi.keyboardReadable(loaded));
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 200, 160);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            loaded.requestFocus();
            loaded.getSelectionModel().selectFirst();
            return loaded;
        });
        try {
            AdminUi.RowInspector inspector = inspectorReference.get();
            assertThat(inspector).as("the mount built the row inspector").isNotNull();
            assertThat(inspector.isShowing()).as("nothing is shown before the shortcut").isFalse();

            onFx(() -> {
                table.fireEvent(key(KeyCode.F2));
                return true;
            });
            waitForFx(inspector::isShowing, Duration.ofSeconds(5));
            assertThat(inspector.text().getText())
                    .as("the whole row, labelled, rather than one clipped cell")
                    .isEqualTo("Address: " + value);

            onFx(() -> {
                table.fireEvent(key(KeyCode.ESCAPE));
                return true;
            });
            waitForFx(() -> !inspector.isShowing(), Duration.ofSeconds(5));

            onFx(() -> {
                table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                        false, true, false, false));
                return true;
            });
            assertThat(onFx(() -> Clipboard.getSystemClipboard().getString()))
                    .as("the copy shortcut takes the whole value, not the shortened hover")
                    .isEqualTo(value);
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The keyboard can name one cell, not only a row: the arrows walk the columns that explain
     * themselves, the reached cell is marked and shows its explanation, and the copy shortcut takes
     * that cell's value. F2 still opens the whole row as the fallback.
     */
    @Test
    void theKeyboardWalksCellsAndCopiesTheFocusedOne() throws Exception {
        String clippedValue = "Mallory Q. Reported-Example, 3 Report Lane, Apartment 14";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<AdminUi.RowInspector> inspectorReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(
                    FXCollections.observableArrayList(clippedValue));
            TableColumn<String, String> address = new TableColumn<>("Address");
            AdminUi.bind(address, value -> value);
            AdminUi.explainTruncatedText(address, ENGLISH);
            TableColumn<String, String> state = new TableColumn<>("State");
            AdminUi.bind(state, value -> "ok");
            AdminUi.explainTruncatedText(state, ENGLISH);
            loaded.getColumns().addAll(List.of(address, state));
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            inspectorReference.set(AdminUi.keyboardReadable(loaded));
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 200, 160);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            loaded.requestFocus();
            loaded.getSelectionModel().selectFirst();
            return loaded;
        });
        try {
            TableCell<?, ?> addressCell = onFx(() -> cellWithText(table, clippedValue));
            TableCell<?, ?> stateCell = onFx(() -> cellWithText(table, "ok"));
            assertThat(addressCell).as("the clipped cell is rendered").isNotNull();
            assertThat(stateCell).as("the neighbouring cell is rendered").isNotNull();
            String textBefore = onFx(() -> textBounds(addressCell));

            // The first arrow names the first column that explains itself.
            onFx(() -> {
                table.fireEvent(key(KeyCode.RIGHT));
                return true;
            });
            waitForFx(() -> addressCell.getStyleClass().contains(AdminUi.KEYBOARD_FOCUS_CLASS),
                    Duration.ofSeconds(5));
            assertThat(onFx(() -> textBounds(addressCell)))
                    .as("marking the cell must not move its text")
                    .isEqualTo(textBefore);
            Tooltip explanation = onFx(addressCell::getTooltip);
            assertThat(explanation).as("the reached cell has an explanation").isNotNull();
            waitForFx(explanation::isShowing, Duration.ofSeconds(5));

            onFx(() -> {
                table.fireEvent(shortcut(KeyCode.C));
                return true;
            });
            assertThat(onFx(() -> Clipboard.getSystemClipboard().getString()))
                    .as("the copy shortcut takes the reached cell, not the row")
                    .isEqualTo(clippedValue);

            // The next arrow moves the cursor on, taking the explanation with it.
            onFx(() -> {
                table.fireEvent(key(KeyCode.RIGHT));
                return true;
            });
            waitForFx(() -> stateCell.getStyleClass().contains(AdminUi.KEYBOARD_FOCUS_CLASS),
                    Duration.ofSeconds(5));
            waitForFx(() -> !explanation.isShowing(), Duration.ofSeconds(5));
            assertThat(addressCell.getStyleClass()).doesNotContain(AdminUi.KEYBOARD_FOCUS_CLASS);
            onFx(() -> {
                table.fireEvent(shortcut(KeyCode.C));
                return true;
            });
            assertThat(onFx(() -> Clipboard.getSystemClipboard().getString())).isEqualTo("ok");

            // The row stays one F2 away.
            AdminUi.RowInspector inspector = inspectorReference.get();
            onFx(() -> {
                table.fireEvent(key(KeyCode.F2));
                return true;
            });
            waitForFx(inspector::isShowing, Duration.ofSeconds(5));
            assertThat(inspector.text().getText())
                    .as("the row inspector still names every column")
                    .isEqualTo("Address: " + clippedValue + "\nState: ok");
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * A screen reader cannot see the mark, so the cursor has to say where it is: the column, the
     * whole value, and whether the screen is showing only part of it. Leaving the cell takes the
     * description away again, so the next cell is described on its own terms.
     */
    @Test
    void theCursorDescribesTheCellToAssistiveTechnology() throws Exception {
        String clippedValue = "Mallory Q. Reported-Example, 3 Report Lane, Apartment 14";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<Button> outsideReference = new AtomicReference<>();
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(clippedValue));
            TableColumn<String, String> address = new TableColumn<>("Address");
            AdminUi.bind(address, value -> value);
            AdminUi.explainTruncatedText(address, ENGLISH);
            TableColumn<String, String> state = new TableColumn<>("State");
            AdminUi.bind(state, value -> "ok");
            AdminUi.explainTruncatedText(state, ENGLISH);
            loaded.getColumns().addAll(List.of(address, state));
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            AdminUi.keyboardReadable(loaded);
            // A second control gives the table somewhere to lose focus to.
            Button outside = new Button("elsewhere");
            outsideReference.set(outside);
            VBox root = new VBox(loaded, outside);
            VBox.setVgrow(loaded, javafx.scene.layout.Priority.ALWAYS);
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(root, 200, 200);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            loaded.requestFocus();
            loaded.getSelectionModel().selectFirst();
            return loaded;
        });
        try {
            TableCell<?, ?> addressCell = onFx(() -> cellWithText(table, clippedValue));
            TableCell<?, ?> stateCell = onFx(() -> cellWithText(table, "ok"));
            assertThat(addressCell.getAccessibleText())
                    .as("before the cursor arrives, a clipped cell reads as its whole value")
                    .isEqualTo(clippedValue);

            onFx(() -> {
                table.fireEvent(key(KeyCode.RIGHT));
                return true;
            });
            waitForFx(() -> addressCell.getStyleClass().contains(AdminUi.KEYBOARD_FOCUS_CLASS),
                    Duration.ofSeconds(5));
            assertThat(onFx(addressCell::getAccessibleText))
                    .as("the cursor names the column, the whole value and the clipping")
                    .isEqualTo("Address: " + clippedValue + " (clipped)");

            onFx(() -> {
                table.fireEvent(key(KeyCode.RIGHT));
                return true;
            });
            waitForFx(() -> stateCell.getStyleClass().contains(AdminUi.KEYBOARD_FOCUS_CLASS),
                    Duration.ofSeconds(5));
            assertThat(onFx(stateCell::getAccessibleText))
                    .as("a cell that fits is named without claiming a clipping")
                    .isEqualTo("State: ok");
            assertThat(onFx(addressCell::getAccessibleText))
                    .as("the cell the cursor left goes back to the plain whole value")
                    .isEqualTo(clippedValue);

            onFx(() -> {
                outsideReference.get().requestFocus();
                return true;
            });
            waitForFx(() -> !table.isFocused(), Duration.ofSeconds(5));
            assertThat(onFx(stateCell::getAccessibleText))
                    .as("leaving the table clears the cursor's description")
                    .isNull();
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /**
     * The row fallback has to be usable, not only visible: a tooltip cannot take focus, scroll or
     * have its text selected, so F2 opens a real text area. The caret lands inside it, the whole row
     * is there, a row taller than the window is capped and scrolls, a selection can be copied with
     * the platform's shortcut, and F2 closes it and gives the table its keyboard back.
     */
    @Test
    void theRowInspectorTakesFocusScrollsAndCopies() throws Exception {
        String value = "Mallory Q. Reported-Example, 3 Report Lane, Apartment 14";
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<AdminUi.RowInspector> inspectorReference = new AtomicReference<>();
        int fields = 12;
        TableView<String> table = onFx(() -> {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            TableView<String> loaded = new TableView<>(FXCollections.observableArrayList(value));
            for (int index = 0; index < fields; index++) {
                int field = index + 1;
                TableColumn<String, String> column = new TableColumn<>("Field " + field);
                AdminUi.bind(column, row -> "value " + field + " for " + row.substring(0, 12));
                loaded.getColumns().add(column);
            }
            loaded.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            inspectorReference.set(AdminUi.keyboardReadable(loaded));
            Stage stage = new Stage();
            stageReference.set(stage);
            Scene scene = new Scene(loaded, 420, 200);
            theme.apply(scene);
            AdminViewLoader.applyStylesheet(scene);
            stage.setScene(scene);
            stage.show();
            loaded.applyCss();
            loaded.layout();
            loaded.requestFocus();
            loaded.getSelectionModel().selectFirst();
            return loaded;
        });
        try {
            AdminUi.RowInspector inspector = inspectorReference.get();
            assertThat(inspector.isShowing()).as("nothing is shown before F2").isFalse();

            onFx(() -> {
                table.fireEvent(key(KeyCode.F2));
                return true;
            });
            waitForFx(inspector::isShowing, Duration.ofSeconds(5));
            TextArea text = inspector.text();
            waitForFx(text::isFocused, Duration.ofSeconds(5));
            assertThat(text.isEditable())
                    .as("read-only, so a stray keystroke cannot lose the row")
                    .isFalse();
            String shown = onFx(text::getText);
            assertThat(shown)
                    .as("the whole row, every column labelled")
                    .startsWith("Field 1: value 1 for ")
                    .contains("\nField " + fields + ": value " + fields + " for ");
            assertThat(onFx(() -> {
                text.applyCss();
                text.layout();
                return text.lookup(".scroll-pane") != null;
            })).as("longer than the window, so the text area brings its own scrolling")
                    .isTrue();
            Double prefHeight = onFx(text::getPrefHeight);
            assertThat(prefHeight).as("capped instead of growing with the row")
                    .isLessThanOrEqualTo(180.0);

            // A part of the row, not only all of it: selecting and copying is what a tooltip could
            // not offer.
            onFx(() -> {
                text.selectRange(0, "Field 1: value 1 for".length());
                return true;
            });
            assertThat(onFx(text::getSelectedText)).isEqualTo("Field 1: value 1 for");
            onFx(() -> {
                text.fireEvent(shortcut(KeyCode.C));
                return true;
            });
            assertThat(onFx(() -> Clipboard.getSystemClipboard().getString()))
                    .as("the platform's copy shortcut takes the selection")
                    .isEqualTo("Field 1: value 1 for");

            onFx(() -> {
                text.fireEvent(key(KeyCode.F2));
                return true;
            });
            waitForFx(() -> !inspector.isShowing(), Duration.ofSeconds(5));
            waitForFx(table::isFocused, Duration.ofSeconds(5));
        } finally {
            onFx(() -> {
                stageReference.get().close();
                return true;
            });
        }
    }

    /** Where the cell's text sits, so a marker that moves it fails instead of looking fine. */
    private static String textBounds(TableCell<?, ?> cell) {
        Node text = cell.lookup(".text");
        javafx.geometry.Bounds bounds = text.localToScene(text.getBoundsInLocal());
        return bounds.getMinX() + "," + bounds.getMinY() + "," + bounds.getWidth();
    }

    private static KeyEvent shortcut(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    /**
     * Signs in against the fake server and mounts one admin screen on a window of its own. Every
     * screen test here needs the same off-thread sign-in, light theme, stylesheet and teardown, so
     * this keeps that sequence in one place; the caller only names the screen and the window size.
     */
    private static Mounted mountedScreen(FakeAdminServer server, String view,
            Function<AdminAppContext, Object> controller, int width, int height) throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        I18n i18n = new I18n(BASE, Locale.ENGLISH);
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AdminAppContext context = onFx(() -> {
            Stage stage = new Stage();
            stageReference.set(stage);
            return new AdminAppContext(stage, server.origin().toString(), theme, i18n);
        });
        Stage stage = stageReference.get();
        try {
            // Three blocking HTTP calls, kept off the FX thread like the other admin suites.
            var signedIn = context.signIn("admin", "correct horse battery staple".toCharArray());
            Parent root = onFx(() -> {
                context.activate(signedIn);
                Parent loaded = AdminViewLoader.load(view, controller.apply(context), i18n);
                Scene scene = new Scene(loaded, width, height);
                theme.apply(scene);
                AdminViewLoader.applyStylesheet(scene);
                stage.setScene(scene);
                stage.show();
                loaded.applyCss();
                loaded.layout();
                return loaded;
            });
            return new Mounted(root, stage, context, theme);
        } catch (Exception failed) {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
            throw failed;
        }
    }

    /** An admin screen on its own window, with the signed-in context behind it. */
    private record Mounted(Parent root, Stage stage, AdminAppContext context, ThemeManager theme)
            implements AutoCloseable {
        @Override
        public void close() {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
        }
    }

    /** Waits for a screen's first rows, then lays it out again so the cells are measured. */
    private static Parent afterFirstRows(Parent root, String tableId) throws Exception {
        waitForFx(() -> tableRows(root, tableId) > 0, Duration.ofSeconds(10));
        onFx(() -> {
            root.applyCss();
            root.layout();
            return true;
        });
        return root;
    }

    /** Shows {@code explanation} as it is and returns its rendered {width, height}. */
    /** Whether {@code offset} starts an extended grapheme cluster in {@code text}. */
    private static boolean isClusterBoundary(String text, int offset) {
        BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
        clusters.setText(text);
        for (int boundary = clusters.first(); boundary != BreakIterator.DONE;
             boundary = clusters.next()) {
            if (boundary == offset) return true;
            if (boundary > offset) return false;
        }
        return false;
    }

    private static double[] shownSize(Tooltip explanation, Node owner) throws Exception {
        onFx(() -> {
            explanation.show(owner, 40, 40);
            return true;
        });
        waitForFx(() -> explanation.isShowing() && explanation.getWidth() > 0
                && explanation.getHeight() > 0, Duration.ofSeconds(5));
        return onFx(() -> new double[] { explanation.getWidth(), explanation.getHeight() });
    }

    /** Shows {@code explanation} and returns the size its own content label resolves. */
    private static double shownFontSize(Tooltip explanation, Node owner) throws Exception {
        onFx(() -> {
            explanation.show(owner, 40, 40);
            return true;
        });
        waitForFx(() -> explanation.isShowing() && explanation.getScene() != null,
                Duration.ofSeconds(5));
        return onFx(() -> {
            for (javafx.scene.Node node : ((Parent) explanation.getScene().getRoot()).lookupAll("*")) {
                if (node instanceof Label label && label.getStyleClass().contains("tooltip")) {
                    return (double) label.getFont().getSize();
                }
            }
            return 0.0;
        });
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
        return cellWithText(table, text);
    }

    private static TableCell<?, ?> cellWithText(TableView<?> table, String text) {
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
