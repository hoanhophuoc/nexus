package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrast checks against JavaFX skins that have actually been laid out.
 *
 * <p>The popup tests deliberately open the real ComboBox/DatePicker popup and read
 * colours from the rendered cells and popup regions. They do not read design tokens:
 * a stylesheet regression must fail here even when the token pairs still pass.</p>
 */
class RenderedControlContrastFxTest {
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass DISABLED = PseudoClass.getPseudoClass("disabled");

    @Test
    void comboBoxPopupSkinMeetsAaInLightAndDark() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            ComboBox<String> combo = new ComboBox<>(
                    FXCollections.observableArrayList("Alpha", "Bravo", "Charlie", "Delta"));
            combo.getSelectionModel().select(0);
            VBox root = new VBox(combo);
            root.getStyleClass().add("app-root");
            Scene scene = new Scene(root, 420, 260);
            theme.apply(scene);
            stage.setScene(scene);
            stage.show();
            try {
                for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
                    theme.setTheme(value);
                    theme.apply(scene);
                    root.applyCss();
                    root.layout();
                    combo.show();
                    try {
                        Parent popupRoot = popupRoot(stage);
                        popupRoot.applyCss();
                        popupRoot.layout();
                        ListView<?> list = (ListView<?>) popupRoot.lookup(".list-view");
                        assertThat(list).as("%s ComboBox popup list", value).isNotNull();
                        Color popupBackground = effectiveBackground(list, null);
                        assertThat(popupBackground).as("%s popup background", value).isNotNull();

                        List<? extends ListCell<?>> cells = popupRoot.lookupAll(".list-cell").stream()
                                .filter(ListCell.class::isInstance)
                                .map(node -> (ListCell<?>) node)
                                .filter(cell -> cell.getItem() != null)
                                .toList();
                        assertThat(cells).as("%s ComboBox popup cells", value).hasSizeGreaterThanOrEqualTo(3);

                        ListCell<?> selected = cells.stream()
                                .filter(cell -> pseudo(cell, "selected"))
                                .findFirst().orElseThrow();
                        ListCell<?> normal = cells.stream()
                                .filter(cell -> !pseudo(cell, "selected") && !pseudo(cell, "hover"))
                                .findFirst().orElseThrow();
                        ListCell<?> hover = cells.stream()
                                .filter(cell -> cell != normal && cell != selected)
                                .findFirst().orElseThrow();

                        measure(value, "combo popup normal cell", textFill(normal),
                                effectiveBackground(normal, popupBackground), 4.5);
                        measure(value, "combo popup selected cell", textFill(selected),
                                effectiveBackground(selected, popupBackground), 4.5);

                        hover.pseudoClassStateChanged(HOVER, true);
                        popupRoot.applyCss();
                        popupRoot.layout();
                        measure(value, "combo popup hover cell", textFill(hover),
                                effectiveBackground(hover, popupBackground), 4.5);
                        hover.pseudoClassStateChanged(HOVER, false);
                    } finally {
                        combo.hide();
                    }
                }
                return true;
            } finally {
                combo.hide();
                stage.close();
            }
        })).isTrue();
    }

    @Test
    void datePickerPopupAndEditorSkinMeetAaInLightAndDark() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            DatePicker date = new DatePicker(LocalDate.of(2026, 11, 10));
            // The editor prompt is bound to ComboBoxBase.promptText and only renders
            // while the editor is empty, so an unvalued picker carries the placeholder.
            DatePicker empty = new DatePicker();
            empty.setPromptText("Pick a date");
            VBox root = new VBox(date, empty);
            root.getStyleClass().add("app-root");
            Scene scene = new Scene(root, 420, 260);
            theme.apply(scene);
            stage.setScene(scene);
            stage.show();
            try {
                for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
                    theme.setTheme(value);
                    theme.apply(scene);
                    root.applyCss();
                    root.layout();

                    TextFieldSkin editor = editorSkin(date);
                    measure(value, "date editor text", editor.textFill(),
                            effectiveBackground(date, null), 4.5);
                    measure(value, "date editor placeholder", editorSkin(empty).promptFill(),
                            effectiveBackground(empty, null), 4.5);

                    date.show();
                    try {
                        Parent popupRoot = popupRoot(stage);
                        popupRoot.applyCss();
                        popupRoot.layout();
                        Region popup = (Region) popupRoot.lookup(".date-picker-popup");
                        assertThat(popup).as("%s DatePicker popup", value).isNotNull();
                        Color popupBackground = effectiveBackground(popup, null);
                        assertThat(popupBackground).as("%s DatePicker popup background", value).isNotNull();

                        Parent monthPane = (Parent) popupRoot.lookup(".month-year-pane");
                        assertThat(monthPane).as("%s DatePicker month/year pane", value).isNotNull();
                        Label monthLabel = (Label) monthPane.lookup(".label");
                        assertThat(monthLabel).as("%s DatePicker month/year label", value).isNotNull();
                        measure(value, "date month/year label", textFill(monthLabel),
                                effectiveBackground((Region) monthPane, popupBackground), 4.5);

                        List<Labeled> days = popupRoot.lookupAll(".day-cell").stream()
                                .filter(Labeled.class::isInstance)
                                .map(Labeled.class::cast)
                                .filter(cell -> cell.getText() != null && !cell.getText().isBlank())
                                .toList();
                        assertThat(days).as("%s DatePicker day cells", value).hasSizeGreaterThan(10);

                        // DateCell marks the chosen day with the "selected" style class,
                        // unlike ListCell, which uses a pseudo-class.
                        Labeled selected = days.stream()
                                .filter(cell -> cell.getStyleClass().contains("selected"))
                                .findFirst().orElseThrow();
                        Labeled normal = days.stream()
                                .filter(cell -> !cell.getStyleClass().contains("selected")
                                        && !cell.isDisabled())
                                .findFirst().orElseThrow();
                        Labeled disabled = days.stream()
                                .filter(Node::isDisabled)
                                .findFirst().orElse(null);
                        Labeled disabledProbe = disabled == null ? normal : disabled;
                        if (disabled == null) {
                            disabledProbe.pseudoClassStateChanged(DISABLED, true);
                            popupRoot.applyCss();
                            popupRoot.layout();
                        }

                        measure(value, "date day normal", textFill(normal),
                                effectiveBackground((Region) normal, popupBackground), 4.5);
                        measure(value, "date day selected", textFill(selected),
                                effectiveBackground((Region) selected, popupBackground), 4.5);
                        measure(value, "date day disabled", textFill(disabledProbe),
                                effectiveBackground((Region) disabledProbe, popupBackground), 4.5);

                        Labeled hover = days.stream()
                                .filter(cell -> cell != normal && cell != selected && !cell.isDisabled())
                                .findFirst().orElseThrow();
                        hover.pseudoClassStateChanged(HOVER, true);
                        popupRoot.applyCss();
                        popupRoot.layout();
                        measure(value, "date day hover", textFill(hover),
                                effectiveBackground((Region) hover, popupBackground), 4.5);
                        hover.pseudoClassStateChanged(HOVER, false);
                        if (disabled == null) {
                            disabledProbe.pseudoClassStateChanged(DISABLED, false);
                        }
                    } finally {
                        date.hide();
                    }
                }
                return true;
            } finally {
                date.hide();
                empty.hide();
                stage.close();
            }
        })).isTrue();
    }

    @Test
    void remainingDisabledControlsMeetAaInLightAndDark() {
        assertThat(onFx(() -> {
            Stage stage = new Stage();
            ThemeManager theme = new ThemeManager();
            ToggleButton toggle = new ToggleButton("Toggle");
            ToggleButton selectedToggle = new ToggleButton("Selected toggle");
            selectedToggle.setSelected(true);
            Hyperlink link = new Hyperlink("Help");
            MenuButton menuButton = new MenuButton("Actions");
            MenuItem disabledItem = new MenuItem("Disabled menu item");
            disabledItem.setDisable(true);
            MenuButton menuWithItem = new MenuButton("Open menu", null, disabledItem);
            menuWithItem.getItems().add(new MenuItem("Enabled menu item"));
            Spinner<Integer> spinner = new Spinner<>(0, 10, 4);

            record Row(String name) { }
            ListView<String> list = new ListView<>(
                    FXCollections.observableArrayList("First", "Second", "Third"));
            TableView<Row> table = new TableView<>();
            TableColumn<Row, String> column = new TableColumn<>("Name");
            column.setCellValueFactory(data -> new javafx.beans.property.ReadOnlyStringWrapper(
                    data.getValue().name()));
            column.setCellFactory(ignored -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
            });
            table.getColumns().add(column);
            table.getItems().addAll(new Row("First"), new Row("Second"));

            toggle.setDisable(true);
            selectedToggle.setDisable(true);
            link.setDisable(true);
            menuButton.setDisable(true);
            spinner.setDisable(true);

            VBox root = new VBox(8, toggle, selectedToggle, link, menuButton, menuWithItem,
                    spinner, list, table);
            root.getStyleClass().add("app-root");
            Scene scene = new Scene(root, 520, 620);
            theme.apply(scene);
            stage.setScene(scene);
            stage.show();
            try {
                for (Theme value : List.of(Theme.LIGHT, Theme.DARK)) {
                    theme.setTheme(value);
                    theme.apply(scene);
                    root.applyCss();
                    root.layout();

                    Color canvas = effectiveBackground(root, Color.WHITE);
                    assertDisabled(value, toggle, canvas, "toggle");
                    assertDisabled(value, selectedToggle, canvas, "selected toggle");
                    assertDisabledTextOnly(value, link, canvas, "hyperlink");
                    assertDisabled(value, menuButton, canvas, "menu button");

                    TextFieldSkin spinnerEditor = new TextFieldSkin(
                            (javafx.scene.control.TextField) spinner.lookup(".text-field"));
                    measure(value, "spinner editor text", spinnerEditor.textFill(),
                            effectiveBackground(spinner, canvas), 4.5);

                    List<? extends ListCell<?>> cells = list.lookupAll(".list-cell").stream()
                            .filter(ListCell.class::isInstance)
                            .map(node -> (ListCell<?>) node)
                            .filter(cell -> cell.getItem() != null)
                            .toList();
                    assertThat(cells).as("%s ListView cells", value).isNotEmpty();
                    ListCell<?> disabledCell = cells.getFirst();
                    disabledCell.setDisable(true);
                    disabledCell.applyCss();
                    assertDisabledCell(value, disabledCell, canvas, "list cell");

                    List<Node> rows = list.lookupAll(".list-cell").stream()
                            .filter(node -> node instanceof ListCell<?> cell
                                    && "Third".equals(cell.getItem()))
                            .toList();
                    assertThat(rows).as("%s third list cell", value).isNotEmpty();

                    Region row = (Region) table.lookupAll(".table-row-cell").stream()
                            .filter(node -> !node.getPseudoClassStates().isEmpty()
                                    || node instanceof javafx.scene.control.TableRow<?>)
                            .findFirst().orElse(null);
                    assertThat(row).as("%s TableView row", value).isNotNull();
                    row.setDisable(true);
                    row.applyCss();
                    assertDisabledCell(value, row, canvas, "table row");

                    menuWithItem.show();
                    try {
                        Parent menuRoot = popupRoot(stage);
                        theme.apply(menuRoot.getScene());
                        menuRoot.applyCss();
                        menuRoot.layout();
                        Node menuItem = menuRoot.lookup(".menu-item");
                        assertThat(menuItem).as("%s disabled menu item", value).isNotNull();
                        Label label = (Label) menuItem.lookup(".label");
                        assertThat(label).as("%s disabled menu item label", value).isNotNull();
                        measure(value, "disabled menu item", textFill(label),
                                effectiveBackground((Region) menuRoot.lookup(".context-menu"), canvas), 4.5);
                        assertThat(menuItem.getOpacity()).as("%s disabled menu item opacity", value)
                                .isEqualTo(1.0);
                    } finally {
                        menuWithItem.hide();
                    }
                }
                return true;
            } finally {
                menuWithItem.hide();
                stage.close();
            }
        })).isTrue();
    }

    private static void assertDisabled(Theme theme, Labeled control, Color fallback, String label) {
        assertThat(control.getOpacity()).as("%s %s opacity", theme, label).isEqualTo(1.0);
        Color background = effectiveBackground((Region) control, fallback);
        assertEqualsColor(theme + " " + label + " background", background,
                theme == Theme.LIGHT ? Color.web("#EEF2F7") : Color.web("#1B2430"));
        assertEqualsColor(theme + " " + label + " text", textFill(control),
                theme == Theme.LIGHT ? Color.web("#566476") : Color.web("#9AA7BA"));
        measure(theme, "disabled " + label, textFill(control), background, 4.5);
    }

    private static void assertDisabledTextOnly(Theme theme, Labeled control, Color fallback,
                                               String label) {
        assertThat(control.getOpacity()).as("%s %s opacity", theme, label).isEqualTo(1.0);
        assertEqualsColor(theme + " " + label + " text", textFill(control),
                theme == Theme.LIGHT ? Color.web("#566476") : Color.web("#9AA7BA"));
        measure(theme, "disabled " + label, textFill(control), fallback, 4.5);
    }

    private static void assertDisabledCell(Theme theme, Node cell, Color fallback, String label) {
        assertThat(cell.getOpacity()).as("%s %s opacity", theme, label).isEqualTo(1.0);
        Color background = effectiveBackground((Region) cell, fallback);
        assertEqualsColor(theme + " " + label + " background", background,
                theme == Theme.LIGHT ? Color.web("#EEF2F7") : Color.web("#1B2430"));
        Labeled labeled = cell instanceof Labeled value ? value : (Labeled) cell.lookup(".table-cell");
        assertThat(labeled).as("%s %s labeled text", theme, label).isNotNull();
        assertEqualsColor(theme + " " + label + " text", textFill(labeled),
                theme == Theme.LIGHT ? Color.web("#566476") : Color.web("#9AA7BA"));
        measure(theme, "disabled " + label, textFill(labeled), background, 4.5);
    }

    private static TextFieldSkin editorSkin(DatePicker date) {
        return new TextFieldSkin((javafx.scene.control.TextField) date.lookup(".text-field"));
    }

    private record TextFieldSkin(Text text, Text prompt) {
        TextFieldSkin(javafx.scene.control.TextField field) {
            this(textNode(field), promptNode(field));
        }

        Color textFill() {
            return (Color) text.getFill();
        }

        Color promptFill() {
            assertThat(prompt).as("text field .prompt-text node").isNotNull();
            return (Color) prompt.getFill();
        }
    }

    private static Text textNode(Parent owner) {
        Node node = owner.lookup(".text");
        assertThat(node).as("rendered .text node").isInstanceOf(Text.class);
        return (Text) node;
    }

    // JavaFX renders the prompt as a second ".text" node instead of a ".prompt-text" node.
    private static Text promptNode(javafx.scene.control.TextField owner) {
        String prompt = owner.getPromptText();
        if (prompt == null || prompt.isEmpty()) {
            return null;
        }
        return owner.lookupAll(".text").stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .filter(text -> prompt.equals(text.getText()))
                .findFirst().orElse(null);
    }

    private static Color textFill(Node node) {
        Node text = node.lookup(".text");
        if (text instanceof Text rendered && rendered.getFill() instanceof Color colour) {
            return colour;
        }
        if (node instanceof Labeled labeled && labeled.getTextFill() instanceof Color colour) {
            return colour;
        }
        throw new AssertionError("No rendered text fill on " + node);
    }

    private static Color effectiveBackground(Region region, Color fallback) {
        Background background = region.getBackground();
        if (background != null) {
            for (BackgroundFill fill : background.getFills()) {
                if (fill.getFill() instanceof Color colour && colour.getOpacity() >= 0.99) {
                    return colour;
                }
            }
        }
        Parent parent = region.getParent();
        while (parent instanceof Region parentRegion) {
            Color resolved = effectiveBackground(parentRegion, null);
            if (resolved != null) return resolved;
            parent = parentRegion.getParent();
        }
        return fallback;
    }

    private static Color effectiveBackground(Node node, Color fallback) {
        return node instanceof Region region ? effectiveBackground(region, fallback) : fallback;
    }

    private static boolean pseudo(Node node, String name) {
        return node.getPseudoClassStates().contains(PseudoClass.getPseudoClass(name));
    }

    private static Parent popupRoot(Stage owner) {
        for (Window window : Window.getWindows()) {
            if (window == owner || !window.isShowing() || !(window instanceof PopupWindow)) continue;
            Scene popupScene = window.getScene();
            if (popupScene != null && popupScene.getRoot() != null) return popupScene.getRoot();
        }
        throw new AssertionError("No showing JavaFX popup found");
    }

    private static void measure(Theme theme, String label, Color foreground, Color background,
                                double minimum) {
        assertThat(foreground).as("%s %s foreground", theme, label).isNotNull();
        assertThat(background).as("%s %s background", theme, label).isNotNull();
        double ratio = contrast(foreground, background);
        System.out.printf("[contrast] %-5s %-30s %.2f:1%n", theme, label, ratio);
        assertThat(ratio).as("%s %s contrast %.2f:1", theme, label, ratio)
                .isGreaterThanOrEqualTo(minimum);
    }

    private static void assertEqualsColor(String label, Color actual, Color expected) {
        assertThat(actual).as(label)
                .usingComparator((first, second) -> {
                    if (first == null || second == null) return first == second ? 0 : 1;
                    double delta = Math.abs(first.getRed() - second.getRed())
                            + Math.abs(first.getGreen() - second.getGreen())
                            + Math.abs(first.getBlue() - second.getBlue());
                    return delta < 0.01 ? 0 : 1;
                })
                .isEqualTo(expected);
    }

    private static double contrast(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color colour) {
        return 0.2126 * channel(colour.getRed())
                + 0.7152 * channel(colour.getGreen())
                + 0.0722 * channel(colour.getBlue());
    }

    private static double channel(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
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
                throw new IllegalStateException("JavaFX toolkit did not start within 30 seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }
}
