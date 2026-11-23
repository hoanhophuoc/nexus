package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static com.nexus.desktop.shared.testing.RenderedContrast.assertEqualsColor;
import static com.nexus.desktop.shared.testing.RenderedContrast.effectiveBackground;
import static com.nexus.desktop.shared.testing.RenderedContrast.measure;
import static com.nexus.desktop.shared.testing.RenderedContrast.textFill;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrast checks against admin skins that have actually been laid out, in both themes.
 *
 * <p>The user client reads rendered popup and control colours; the administrator client had no
 * equivalent, so a token that failed to reach its tables or its primary action was only visible in
 * a golden image, where a 0.75% pixel tolerance and a small surface can hide it. These cases read
 * the colours the admin screens paint from real FXML, and they also pin the token each surface is
 * supposed to carry: a table that silently fell back to the toolkit palette fails here even when
 * its own text still happens to contrast.</p>
 */
class RenderedAdminContrastFxTest {
    private static final String BASE = "com.nexus.desktop.admin.i18n.messages";
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    @Test
    void loginScreenSkinsMeetAaInLightAndDark() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            I18n english = new I18n(BASE, Locale.ENGLISH);
            AdminAppContext context = new AdminAppContext(stage, "http://127.0.0.1:1", theme, english);
            try {
                Parent root = AdminViewLoader.load(AdminViewLoader.LOGIN,
                        new AdminLoginController(context, profile -> { }), english);
                Scene scene = new Scene(root, 520, 640);
                theme.apply(scene);
                AdminViewLoader.applyStylesheet(scene);
                stage.setScene(scene);
                stage.show();
                for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
                    theme.setTheme(value);
                    theme.apply(scene);
                    root.applyCss();
                    root.layout();

                    assertEqualsColor(value + " login canvas", effectiveBackground((Region) root, null),
                            background(value));
                    Region card = (Region) root.lookup(".login-card");
                    assertThat(card).as("%s login card", value).isNotNull();
                    Color surface = effectiveBackground(card, null);
                    assertEqualsColor(value + " login card surface", surface, surface(value));

                    measure(value, "field label", textFill(root.lookup("#usernameLabel")), surface, 4.5);
                    measure(value, "form feedback", textFill(root.lookup("#feedbackLabel")), surface, 4.5);
                    measure(value, "username prompt",
                            promptFill((TextInputControl) root.lookup("#usernameField")),
                            effectiveBackground((Region) root.lookup("#usernameField"), surface), 4.5);
                    measure(value, "password prompt",
                            promptFill((TextInputControl) root.lookup("#passwordField")),
                            effectiveBackground((Region) root.lookup("#passwordField"), surface), 4.5);

                    Labeled mark = (Labeled) root.lookup(".brand-mark-text");
                    assertThat(mark).as("%s brand mark text", value).isNotNull();
                    Color markBackground = effectiveBackground((Region) mark.getParent(), null);
                    assertEqualsColor(value + " brand mark", markBackground, accent(value));
                    measure(value, "brand mark text", textFill(mark), markBackground, 4.5);

                    Button signIn = (Button) root.lookup("#signInButton");
                    assertThat(signIn).as("%s sign-in button", value).isNotNull();
                    Color signInBackground = effectiveBackground(signIn, null);
                    assertEqualsColor(value + " primary button", signInBackground, accent(value));
                    measure(value, "sign-in label", textFill(signIn), signInBackground, 4.5);
                }
                return true;
            } finally {
                context.close();
                stage.close();
            }
        })).isTrue();
    }

    @Test
    void accountsTableAndDetailSkinsMeetAaInLightAndDark() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer()) {
            Stage stage = onFx(Stage::new);
            ThemeManager theme = onFx(ThemeManager::new);
            I18n english = new I18n(BASE, Locale.ENGLISH);
            AdminAppContext context = onFx(() ->
                    new AdminAppContext(stage, server.origin().toString(), theme, english));
            // Three blocking HTTP calls; the sign-in stays off the FX thread like the app does it.
            AdminAppContext.SignedIn signedIn =
                    context.signIn("admin", "correct horse battery staple".toCharArray());
            onFx(() -> {
                context.activate(signedIn);
                return true;
            });
            Parent root = onFx(() -> {
                Parent loaded = AdminViewLoader.load(AdminViewLoader.ACCOUNTS,
                        new AccountsController(context), english);
                Scene scene = new Scene(loaded, 1280, 720);
                theme.apply(scene);
                AdminViewLoader.applyStylesheet(scene);
                stage.setScene(scene);
                stage.show();
                loaded.applyCss();
                loaded.layout();
                return loaded;
            });
            try {
                TableView<?> accounts = table(root, "#accountsTable");
                waitForFx(() -> !accounts.getItems().isEmpty(), Duration.ofSeconds(20));
                for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
                    onFx(() -> {
                        theme.setTheme(value);
                        theme.apply(root.getScene());
                        root.applyCss();
                        root.layout();
                        measureTable(value, "accounts", root, accounts);
                        measure(value, "accounts status",
                                textFill(root.lookup("#statusLabel")), background(value), 4.5);
                        Button save = (Button) root.lookup("#saveButton");
                        assertThat(save).as("%s detail save button", value).isNotNull();
                        Color saveBackground = effectiveBackground(save, null);
                        assertEqualsColor(value + " detail primary button", saveBackground, accent(value));
                        measure(value, "detail save label", textFill(save), saveBackground, 4.5);
                        measure(value, "detail field label", textFill(root.lookup("#usernameLabel")),
                                effectiveBackground((Region) root.lookup(".admin-detail"), null), 4.5);
                        return true;
                    });
                }
            } finally {
                onFx(() -> {
                    context.close();
                    stage.close();
                    return true;
                });
            }
        }
    }

    @Test
    void reportTablesSkinsMeetAaInLightAndDark() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer()) {
            Stage stage = onFx(Stage::new);
            ThemeManager theme = onFx(ThemeManager::new);
            I18n english = new I18n(BASE, Locale.ENGLISH);
            AdminAppContext context = onFx(() ->
                    new AdminAppContext(stage, server.origin().toString(), theme, english));
            AdminAppContext.SignedIn signedIn =
                    context.signIn("admin", "correct horse battery staple".toCharArray());
            onFx(() -> {
                context.activate(signedIn);
                return true;
            });
            Parent root = onFx(() -> {
                Parent loaded = AdminViewLoader.load(AdminViewLoader.REPORTS,
                        new ReportsController(context), english);
                Scene scene = new Scene(loaded, 1280, 720);
                theme.apply(scene);
                AdminViewLoader.applyStylesheet(scene);
                stage.setScene(scene);
                stage.show();
                loaded.applyCss();
                loaded.layout();
                return loaded;
            });
            try {
                TableView<?> reports = table(root, "#reportsTable");
                TableView<?> evidence = table(root, "#evidenceTable");
                waitForFx(() -> !reports.getItems().isEmpty(), Duration.ofSeconds(20));
                waitForFx(() -> !evidence.getItems().isEmpty(), Duration.ofSeconds(20));
                for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
                    onFx(() -> {
                        theme.setTheme(value);
                        theme.apply(root.getScene());
                        root.applyCss();
                        root.layout();
                        measureTable(value, "reports", root, reports);
                        measureTable(value, "evidence", root, evidence);
                        measure(value, "reports status",
                                textFill(root.lookup("#statusLabel")), background(value), 4.5);
                        return true;
                    });
                }
            } finally {
                onFx(() -> {
                    context.close();
                    stage.close();
                    return true;
                });
            }
        }
    }

    // ---------------------------------------------------------------- reading rendered tables

    /**
     * Header, normal, hovered and selected rows of one admin table, plus the action buttons its
     * cells build. The row surfaces are pinned to their tokens as well as measured, because the
     * admin table styling is a second stylesheet layer over the shared theme: a rule that stopped
     * applying would fall back to the toolkit palette and could still be readable.
     */
    private static void measureTable(Theme value, String name, Parent root, TableView<?> table) {
        table.getSelectionModel().select(0);
        root.applyCss();
        root.layout();

        Region header = (Region) table.lookup(".column-header-background");
        assertThat(header).as("%s %s header", value, name).isNotNull();
        Labeled headerLabel = (Labeled) table.lookup(".column-header .label");
        assertThat(headerLabel).as("%s %s header label", value, name).isNotNull();
        Color headerBackground = effectiveBackground(header, null);
        assertEqualsColor(value + " " + name + " header background", headerBackground, muted(value));
        measure(value, name + " column header", textFill(headerLabel), headerBackground, 4.5);

        List<TableRow<?>> rows = new java.util.ArrayList<>();
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && row.getItem() != null) rows.add(row);
        }
        assertThat(rows).as("%s %s realised rows", value, name).isNotEmpty();
        TableRow<?> selected = rows.stream()
                .filter(row -> row.getPseudoClassStates().contains(SELECTED))
                .findFirst().orElseThrow(() -> new AssertionError(name + " has no selected row"));

        Color selectedBackground = effectiveBackground(selected, null);
        assertEqualsColor(value + " " + name + " selected row background", selectedBackground,
                accentSoft(value));
        measure(value, name + " selected cell", textFill(firstCell(selected)), selectedBackground, 4.5);

        // A screen with a single row still has to show its unselected state: the same row is read
        // again after the selection is cleared, rather than the case being skipped.
        TableRow<?> normal = rows.stream()
                .filter(row -> !row.getPseudoClassStates().contains(SELECTED))
                .findFirst().orElse(null);
        if (normal == null) {
            table.getSelectionModel().clearSelection();
            root.applyCss();
            root.layout();
            normal = selected;
        }
        assertThat(normal.getPseudoClassStates()).as("%s %s unselected row", value, name)
                .doesNotContain(SELECTED);

        Color rowBackground = effectiveBackground(normal, null);
        assertEqualsColor(value + " " + name + " row background", rowBackground, surface(value));
        measure(value, name + " cell", textFill(firstCell(normal)), rowBackground, 4.5);

        normal.pseudoClassStateChanged(HOVER, true);
        root.applyCss();
        root.layout();
        Color hoverBackground = effectiveBackground(normal, null);
        assertEqualsColor(value + " " + name + " hovered row background", hoverBackground,
                muted(value));
        measure(value, name + " hovered cell", textFill(firstCell(normal)), hoverBackground, 4.5);
        normal.pseudoClassStateChanged(HOVER, false);
        root.applyCss();
        root.layout();

        measureAction(value, name + " danger action", selected, ".danger-button");
        measureAction(value, name + " secondary action", selected, ".secondary-button");
    }

    private static void measureAction(Theme value, String label, TableRow<?> row, String selector) {
        Button button = (Button) row.lookup(selector);
        if (button == null) return;
        measure(value, label, textFill(button),
                effectiveBackground(button, effectiveBackground(row, null)), 4.5);
    }

    private static Node firstCell(TableRow<?> row) {
        Node cell = row.lookup(".table-cell");
        assertThat(cell).as("a rendered cell in the row").isNotNull();
        return cell;
    }

    private static TableView<?> table(Parent root, String id) {
        TableView<?> table = (TableView<?>) root.lookup(id);
        assertThat(table).as("the %s table", id).isNotNull();
        return table;
    }

    // ---------------------------------------------------------------- tokens and reading colours

    private static Color background(Theme theme) {
        return theme == Theme.LIGHT ? Color.web("#F4F6FA") : Color.web("#0F141B");
    }

    private static Color surface(Theme theme) {
        return theme == Theme.LIGHT ? Color.web("#FFFFFF") : Color.web("#151C26");
    }

    private static Color muted(Theme theme) {
        return theme == Theme.LIGHT ? Color.web("#EEF2F7") : Color.web("#1B2430");
    }

    private static Color accentSoft(Theme theme) {
        return theme == Theme.LIGHT ? Color.web("#E8EFFF") : Color.web("#1D2C4D");
    }

    private static Color accent(Theme theme) {
        return theme == Theme.LIGHT ? Color.web("#1D5DDC") : Color.web("#6E9BFF");
    }

    /** JavaFX renders a placeholder as a second ".text" node rather than a ".prompt-text" node. */
    private static Color promptFill(TextInputControl field) {
        assertThat(field).as("a rendered text field").isNotNull();
        String prompt = field.getPromptText();
        assertThat(prompt).as("prompt text of the field").isNotBlank();
        Text node = field.lookupAll(".text").stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .filter(text -> prompt.equals(text.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No rendered prompt text: " + prompt));
        return (Color) node.getFill();
    }

}
