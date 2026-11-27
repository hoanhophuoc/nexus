package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.Theme;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    @Test
    void loginScreenSkinsMeetAaInLightAndDark() {
        try (AdminSessions.Session session = AdminSessions.open("http://127.0.0.1:1")) {
            Parent root = session.show(AdminViewLoader.LOGIN,
                    () -> new AdminLoginController(session.context(), profile -> { }), 520, 640);
            inBothThemes(session, root, value -> {
                Color surface = effectiveBackground((Region) root.lookup(".login-card"), null);
                assertEqualsColor(value + " login canvas", effectiveBackground((Region) root, null),
                        background(value));
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

                // The pointer sits wherever the window manager left it under Xvfb, and a real
                // pulse can deliver hover to whatever is under it. This check is the resting
                // state, so hover is cleared rather than left to the environment.
                Button signIn = (Button) root.lookup("#signInButton");
                signIn.pseudoClassStateChanged(HOVER, false);
                root.applyCss();
                Color signInBackground = effectiveBackground(signIn, null);
                assertEqualsColor(value + " primary button", signInBackground, accent(value));
                measure(value, "sign-in label", textFill(signIn), signInBackground, 4.5);
            });
        }
    }

    @Test
    void accountsTableAndDetailSkinsMeetAaInLightAndDark() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             AdminSessions.Session session = AdminSessions.signedIn(server)) {
            Parent root = session.show(AdminViewLoader.ACCOUNTS,
                    () -> new AccountsController(session.context()), 1280, 720);
            TableView<?> accounts = table(root, "#accountsTable");
            waitForFx(() -> !accounts.getItems().isEmpty(), Duration.ofSeconds(20));
            inBothThemes(session, root, value -> {
                measureTable(value, "accounts", root, accounts, true);
                measure(value, "accounts status", textFill(root.lookup("#statusLabel")),
                        background(value), 4.5);
                Color saveBackground = effectiveBackground((Button) root.lookup("#saveButton"), null);
                assertEqualsColor(value + " detail primary button", saveBackground, accent(value));
                measure(value, "detail save label", textFill(root.lookup("#saveButton")),
                        saveBackground, 4.5);
                measure(value, "detail field label", textFill(root.lookup("#usernameLabel")),
                        effectiveBackground((Region) root.lookup(".admin-detail"), null), 4.5);
            });
        }
    }

    @Test
    void reportTablesSkinsMeetAaInLightAndDark() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer();
             AdminSessions.Session session = AdminSessions.signedIn(server)) {
            Parent root = session.show(AdminViewLoader.REPORTS,
                    () -> new ReportsController(session.context()), 1280, 720);
            TableView<?> reports = table(root, "#reportsTable");
            TableView<?> evidence = table(root, "#evidenceTable");
            waitForFx(() -> !reports.getItems().isEmpty(), Duration.ofSeconds(20));
            waitForFx(() -> !evidence.getItems().isEmpty(), Duration.ofSeconds(20));
            inBothThemes(session, root, value -> {
                measureTable(value, "reports", root, reports, true);
                measureTable(value, "evidence", root, evidence, false);
                measure(value, "reports status", textFill(root.lookup("#statusLabel")),
                        background(value), 4.5);
            });
        }
    }

    // ---------------------------------------------------------------- reading rendered tables

    /**
     * Header, normal, hovered and selected rows of one admin table, plus the action buttons its
     * cells build. The row surfaces are pinned to their tokens as well as measured, because the
     * admin table styling is a second stylesheet layer over the shared theme: a rule that stopped
     * applying would fall back to the toolkit palette and could still be readable.
     *
     * <p>{@code actions} says whether the rows are expected to build action buttons; a table whose
     * action column lost them fails rather than passing because there was nothing to measure.
     */
    private static void measureTable(Theme value, String name, Parent root, TableView<?> table,
                                     boolean actions) {
        table.getSelectionModel().select(0);
        root.applyCss();
        root.layout();

        Labeled headerLabel = (Labeled) table.lookup(".column-header .label");
        Color headerBackground = effectiveBackground((Region) table.lookup(".column-header-background"),
                null);
        assertEqualsColor(value + " " + name + " header background", headerBackground, muted(value));
        measure(value, name + " column header", textFill(headerLabel), headerBackground, 4.5);

        List<TableRow<?>> rows = new ArrayList<>();
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && row.getItem() != null) rows.add(row);
        }
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

        measureAction(value, name + " danger action", selected, ".danger-button", actions);
        measureAction(value, name + " secondary action", selected, ".secondary-button", actions);
    }

    private static void measureAction(Theme value, String label, TableRow<?> row, String selector,
                                      boolean required) {
        Button button = (Button) row.lookup(selector);
        if (button == null) {
            if (required) throw new AssertionError("No " + selector + " in the row of " + label);
            return;
        }
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

    // ---------------------------------------------------------------- composition and tokens

    /** Runs the measurements once per theme on the live scene, so both palettes are read. */
    private static void inBothThemes(AdminSessions.Session session, Parent root,
                                     Consumer<Theme> step) {
        for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
            session.window().theme(value);
            onFx(() -> {
                root.applyCss();
                root.layout();
                step.accept(value);
                return true;
            });
        }
    }

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
