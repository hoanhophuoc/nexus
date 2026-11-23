package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.ConfirmDialog;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A popup owns a scene of its own, so it inherits neither the application stylesheet nor the theme
 * class. These cases open the real popup of each control and read the colours the rendered skin
 * paints, in both themes: "it has a skin" is not evidence that the Nexus theme reached it.
 */
class PopupThemeFxTest {
    private static final String LIGHT = "theme-light";
    private static final String DARK = "theme-dark";
    private static final Color LIGHT_SURFACE = Color.web("#FFFFFF");
    private static final Color DARK_SURFACE = Color.web("#151C26");
    private static final Color LIGHT_TEXT = Color.web("#17212F");
    private static final Color DARK_TEXT = Color.web("#E8EDF5");

    @Test
    void aComboBoxPopupTakesTheTokensAndFollowsTheTheme() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        Stage stage = onFx(Stage::new);
        ComboBox<String> combo = mount(stage, theme, new ComboBox<>(
                FXCollections.observableArrayList("Direct chat", "Group chat")));
        try {
            Parent opened = open(".list-view", combo::show);
            assertTheme(opened, LIGHT);
            assertToken(opened, ".list-view", LIGHT_SURFACE);

            onFx(() -> {
                combo.hide();
                return true;
            });
            Parent reopened = open(".list-view", combo::show);
            assertTheme(reopened, LIGHT);
            assertToken(reopened, ".list-view", LIGHT_SURFACE);

            // The theme changes while the popup stays open. A popup that the toolkit rebuilt while
            // the theme moved is not the one still on screen, so the assertion follows the live
            // popup for the marker rather than the node captured before the flip.
            onFx(() -> {
                theme.setTheme(Theme.DARK);
                // The toolkit closes a popup on its own in some environments (a stage losing focus),
                // and a closed popup has no rendering pass to observe. Re-showing it keeps the
                // measurement on a popup that is actually on screen; when the first one survived,
                // this is a no-op and the assertion is about that same popup.
                if (!combo.isShowing()) combo.show();
                return true;
            });
            Parent flipped = onFx(() -> livePopupRoot(".list-view", reopened));
            assertTheme(flipped, DARK);
            awaitToken(flipped, ".list-view", DARK_SURFACE);

            onFx(() -> {
                combo.hide();
                return true;
            });
            Parent afterFlip = open(".list-view", combo::show);
            assertTheme(afterFlip, DARK);
            assertToken(afterFlip, ".list-view", DARK_SURFACE);
        } finally {
            onFx(() -> {
                stage.close();
                return true;
            });
        }
    }

    @Test
    void aDatePickerAndAContextMenuTakeTheTokens() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        Stage stage = onFx(Stage::new);
        DatePicker date = mount(stage, theme, new DatePicker(LocalDate.of(2026, 11, 15)));
        MenuButton menu = onFx(() -> new MenuButton("Actions", null,
                new MenuItem("Delete for me"), new MenuItem("Delete for everyone")));
        try {
            Parent calendar = open(".date-picker-popup", date::show);
            assertTheme(calendar, LIGHT);
            assertToken(calendar, ".date-picker-popup", LIGHT_SURFACE);
            onFx(() -> {
                date.hide();
                return true;
            });

            onFx(() -> {
                ((VBox) date.getParent()).getChildren().add(menu);
                date.getParent().applyCss();
                date.getParent().layout();
                return true;
            });
            Parent context = open(".context-menu", menu::show);
            assertTheme(context, LIGHT);
            assertMenuItemText(context, LIGHT_TEXT);

            onFx(() -> {
                menu.hide();
                theme.setTheme(Theme.DARK);
                return true;
            });
            Parent darkMenu = open(".context-menu", menu::show);
            assertTheme(darkMenu, DARK);
            assertMenuItemText(darkMenu, DARK_TEXT);
        } finally {
            onFx(() -> {
                stage.close();
                return true;
            });
        }
    }

    /**
     * The content walk has to stop at the control that owns virtualized rows: a popup over a long
     * history is styled by stamping the list it holds, not by visiting every realised row each time
     * the popup is shown or the theme moves.
     */
    @Test
    void aLongPopupIsStyledWithoutStampingEveryRow() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.DARK);
        Stage stage = onFx(Stage::new);
        ComboBox<String> combo = onFx(() -> {
            javafx.collections.ObservableList<String> items = FXCollections.observableArrayList();
            for (int index = 0; index < 2_000; index++) items.add("Conversation " + index);
            ComboBox<String> box = new ComboBox<>(items);
            box.getSelectionModel().selectFirst();
            return box;
        });
        mount(stage, theme, combo);
        try {
            Parent popup = open(".list-view", combo::show);
            assertTheme(popup, DARK);
            awaitToken(popup, ".list-view", DARK_SURFACE);

            int realisedRows = onFx(() -> popup.lookupAll(".list-cell").size());
            int stamped = onFx(() -> popup.lookupAll("." + DARK).size());
            assertThat(realisedRows).as("rows the popup actually built").isGreaterThan(5);
            assertThat(stamped).as("nodes carrying the theme class, walk bounded at the list")
                    .isLessThan(10);
        } finally {
            onFx(() -> {
                combo.hide();
                stage.close();
                return true;
            });
        }
    }

    @Test
    void aTooltipTakesTheTokensAndFollowsTheTheme() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        Stage stage = onFx(Stage::new);
        Label target = mount(stage, theme, new Label("Delete for everyone"));
        Tooltip tip = onFx(() -> new Tooltip("Delete for everyone"));
        try {
            onFx(() -> {
                tip.show(target, 120, 120);
                return true;
            });
            Parent popup = awaitPopup(".tooltip");
            assertTheme(popup, LIGHT);
            // The tooltip paints -color-surface on -color-text; reading the rendered text proves
            // the semantic token reached the popup skin.
            assertTextFill(popup, LIGHT_SURFACE);

            onFx(() -> {
                theme.setTheme(Theme.DARK);
                return true;
            });
            assertTheme(popup, DARK);
            // A tooltip hides itself on a timer, so its rendered colour is read on the next show.
            onFx(() -> {
                tip.hide();
                tip.show(target, 120, 120);
                return true;
            });
            Parent reopened = awaitPopup(".tooltip");
            assertTheme(reopened, DARK);
            assertTextFill(reopened, DARK_SURFACE);
        } finally {
            onFx(() -> {
                tip.hide();
                stage.close();
                return true;
            });
        }
    }

    /**
     * A confirmation dialog is a Stage of its own; the dialog pane has to be styled even when no
     * owner window is available, and the class has to follow a theme change while it is open.
     */
    @Test
    void aConfirmationDialogTakesTheTokensAndFollowsTheTheme() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        Stage stage = onFx(Stage::new);
        mount(stage, theme, new Label("body"));
        try {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Platform.runLater(() -> result.complete(ConfirmDialog.show(stage, theme,
                    "Delete conversation", "Delete this conversation for everyone?",
                    "Delete", "Cancel", true)));
            Stage dialog = awaitDialog();
            Parent pane = onFx(() -> {
                Parent root = dialog.getScene().getRoot();
                root.applyCss();
                root.layout();
                return root;
            });
            assertThat(dialog.getScene().getStylesheets()).as("dialog scene stylesheet")
                    .contains(ThemeManager.stylesheetUrl());
            assertThat(pane.getStyleClass()).as("dialog theme class").contains(LIGHT);

            onFx(() -> {
                theme.setTheme(Theme.DARK);
                return true;
            });
            assertThat(pane.getStyleClass()).as("dialog follows the theme").contains(DARK);

            onFx(() -> {
                Button cancel = (Button) dialog.getScene().lookup("#confirmDialogCancel");
                assertThat(cancel).as("the dialog has its cancel action").isNotNull();
                cancel.fire();
                return true;
            });
            assertThat(result.get(10, TimeUnit.SECONDS)).as("cancel closes the dialog").isFalse();
        } finally {
            onFx(() -> {
                stage.close();
                return true;
            });
        }
    }

    /** A manager styles only its own windows: another stage's popup keeps the stock styling. */
    @Test
    void aPopupOnAWindowTheManagerNeverStyledIsLeftAlone() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        Stage themed = onFx(Stage::new);
        mount(themed, theme, new Label("themed"));
        Stage other = onFx(Stage::new);
        ComboBox<String> foreign = onFx(() -> {
            ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList("One", "Two"));
            box.getSelectionModel().selectFirst();
            VBox root = new VBox(box);
            other.setScene(new Scene(root, 320, 200));
            other.show();
            root.applyCss();
            root.layout();
            return box;
        });
        try {
            onFx(() -> {
                themed.close();
                return true;
            });
            Parent popup = open(".list-view", foreign::show);
            assertThat(popup.getScene().getStylesheets()).as("foreign popup stylesheets")
                    .doesNotContain(ThemeManager.stylesheetUrl());
            assertThat(popup.getStyleClass()).as("foreign popup theme class")
                    .doesNotContain(LIGHT, DARK);
        } finally {
            onFx(() -> {
                other.close();
                return true;
            });
        }
    }

    /**
     * The popup listener is one for the whole JVM, so several managers share it. A popup belongs to
     * the manager that owns its owner window: a theme flip in the other manager must not stamp it,
     * and the same flip must still reach that other manager's own window. Without the owner check,
     * whichever manager changed theme last would repaint every popup on the machine.
     */
    @Test
    void oneManagerNeverRestylesAnotherManagersPopup() throws Exception {
        ThemeManager first = new ThemeManager();
        ThemeManager second = new ThemeManager();
        // Both start dark, and the first flips to light: a cross-manager stamp would be visible as
        // a light popup where a dark one is expected, not as two managers agreeing by luck.
        first.setTheme(Theme.DARK);
        second.setTheme(Theme.DARK);
        Stage firstStage = onFx(Stage::new);
        Stage secondStage = onFx(Stage::new);
        ComboBox<String> firstBox = mount(firstStage, first,
                new ComboBox<>(FXCollections.observableArrayList("One", "Two")));
        ComboBox<String> secondBox = mount(secondStage, second,
                new ComboBox<>(FXCollections.observableArrayList("One", "Two")));
        try {
            Parent popup = open(".list-view", secondBox::show);
            assertTheme(popup, DARK);
            assertToken(popup, ".list-view", DARK_SURFACE);

            onFx(() -> {
                first.setTheme(Theme.LIGHT);
                return true;
            });

            assertThat(popup.getStyleClass())
                    .as("theme class of the other manager's popup")
                    .contains(DARK).doesNotContain(LIGHT);
            assertThat(onFx(() -> popup.lookupAll("." + LIGHT)))
                    .as("nodes the other manager stamped inside this popup")
                    .isEmpty();
            assertToken(popup, ".list-view", DARK_SURFACE);
            assertThat(onFx(() -> firstStage.getScene().getRoot().getStyleClass()))
                    .as("the manager's own window followed its theme")
                    .contains(LIGHT);

            onFx(() -> {
                secondBox.hide();
                return true;
            });
            Parent own = open(".list-view", firstBox::show);
            assertTheme(own, LIGHT);
            assertToken(own, ".list-view", LIGHT_SURFACE);
        } finally {
            onFx(() -> {
                secondBox.hide();
                firstBox.hide();
                secondStage.close();
                firstStage.close();
                return true;
            });
        }
    }

    /**
     * A closed owner takes its tracked popup with it: nothing on it is restyled once the window is
     * gone, so a popup that outlives its owner cannot be pulled onto a theme it is no longer shown
     * under. The live stage in the same run is the control — it does restyle.
     */
    @Test
    void closingTheOwnerRemovesItsTrackedPopup() throws Exception {
        ThemeManager theme = new ThemeManager();
        theme.setTheme(Theme.LIGHT);
        Stage closing = onFx(Stage::new);
        ComboBox<String> closingBox = mount(closing,
                theme, new ComboBox<>(FXCollections.observableArrayList("One", "Two")));
        onFx(() -> {
            closingBox.getSelectionModel().selectFirst();
            return true;
        });
        Parent popup = open(".list-view", closingBox::show);
        assertTheme(popup, LIGHT);
        Window popupWindow = onFx(() -> popup.getScene().getWindow());

        Stage live = onFx(Stage::new);
        ComboBox<String> liveBox = mount(live,
                theme, new ComboBox<>(FXCollections.observableArrayList("One", "Two")));
        onFx(() -> {
            liveBox.getSelectionModel().selectFirst();
            return true;
        });
        Parent livePopup = open(".list-view", liveBox::show);
        assertTheme(livePopup, LIGHT);
        try {
            onFx(() -> {
                closingBox.hide();
                closing.close();
                return true;
            });
            waitForFx(() -> !Window.getWindows().contains(popupWindow), Duration.ofSeconds(5));

            onFx(() -> {
                theme.setTheme(Theme.DARK);
                return true;
            });

            assertThat(popup.getStyleClass())
                    .as("theme class of the popup whose owner closed")
                    .contains(LIGHT).doesNotContain(DARK);
            waitForFx(() -> livePopup.getStyleClass().contains(DARK), Duration.ofSeconds(10));
        } finally {
            onFx(() -> {
                liveBox.hide();
                live.close();
                return true;
            });
        }
    }

    // ---------------------------------------------------------------- mounting and reading

    private static <T extends javafx.scene.Node> T mount(Stage stage, ThemeManager theme, T control) {
        return onFx(() -> {
            VBox root = new VBox(12, control);
            Scene scene = new Scene(root, 440, 280);
            theme.apply(scene);
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            return control;
        });
    }

    private static Parent open(String marker, Runnable show) {
        onFx(() -> {
            show.run();
            return true;
        });
        return awaitPopup(marker);
    }

    private static Parent awaitPopup(String marker) {
        waitForFx(() -> popupContaining(marker) != null, Duration.ofSeconds(5));
        return onFx(() -> {
            Parent popup = popupContaining(marker);
            popup.applyCss();
            popup.layout();
            return popup;
        });
    }

    /**
     * The popup {@code previous} refers to if it is still the one on screen, otherwise the live popup
     * that carries the same marker. Hiding and showing a control can make the toolkit build a new
     * popup and drop the old one from {@link Window#getWindows()}; the old node keeps reporting its
     * last state, so asserting on it measures a window nobody is looking at.
     *
     * <p>Reads window state only, so it may run inside an FX task.
     */
    private static Parent livePopupRoot(String marker, Parent previous) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (!(window instanceof PopupWindow) || !window.isShowing()) continue;
            if (window.getScene() == null || window.getScene().getRoot() == null) continue;
            Parent root = window.getScene().getRoot();
            if (root == previous) return root;
        }
        Parent current = popupContaining(marker);
        return current == null ? previous : current;
    }

    /** The shown popup whose scene root, or a node inside it, carries the marker class. */
    private static Parent popupContaining(String marker) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (!(window instanceof PopupWindow) || !window.isShowing()) continue;
            if (window.getScene() == null || window.getScene().getRoot() == null) continue;
            Parent root = window.getScene().getRoot();
            if (root.getStyleClass().contains(marker.substring(1)) || root.lookup(marker) != null) {
                return root;
            }
        }
        return null;
    }

    private static Stage awaitDialog() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Stage found = onFx(() -> {
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    if (window instanceof Stage candidate && candidate.isShowing()
                            && candidate.getScene() != null
                            && candidate.getScene().lookup("#confirmDialogCancel") != null) {
                        return candidate;
                    }
                }
                return null;
            });
            if (found != null) return found;
            Thread.sleep(50);
        }
        throw new AssertionError("The confirmation dialog never opened.");
    }

    private static void assertTheme(Parent popup, String themeClass) {
        assertThat(popup.getScene()).as("popup scene").isNotNull();
        assertThat(popup.getScene().getStylesheets()).as("popup scene stylesheets")
                .contains(ThemeManager.stylesheetUrl());
        assertThat(popup.getStyleClass()).as("theme class on the popup root").contains(themeClass);
    }

    private static void assertToken(Parent popup, String selector, Color expected) {
        Region region = region(popup, selector);
        assertThat(region).as("the %s region", selector).isNotNull();
        assertThat(background(region)).as("%s background in %s", selector, popup.getStyleClass())
                .isEqualTo(expected);
    }

    /** Waits for the CSS pass that repaints a popup whose theme changed while it stayed open. */
    private static void awaitToken(Parent popup, String selector, Color expected) {
        // ThemeManager restyles an open popup by moving the theme class and re-setting the scene's
        // stylesheet list, then running the synchronous CSS-and-layout pass. The test drives that
        // same public pass on whichever popup is on screen — the toolkit can rebuild it, and the
        // node it replaces keeps reporting its last colours — so every read happens after the pass
        // rather than while waiting for a pulse the toolkit may be slow to schedule.
        Parent[] live = {popup};
        waitForFx(() -> {
            Parent target = livePopupRoot(selector, popup);
            Parent sceneRoot = target.getScene() == null ? target : target.getScene().getRoot();
            sceneRoot.applyCss();
            sceneRoot.layout();
            target.snapshot(new SnapshotParameters(), null);
            live[0] = target;
            return expected.equals(background(region(target, selector)));
        }, Duration.ofSeconds(20));
        assertToken(live[0], selector, expected);
        assertToken(popup, selector, expected);
    }

    private static void assertTextFill(Parent popup, Color expected) {
        popup.applyCss();
        assertThat(textFill(popup)).as("tooltip text in %s", popup.getStyleClass())
                .isEqualTo(expected);
    }

    private static Color textFill(Parent popup) {
        javafx.scene.Node text = popup.lookup(".text");
        return text instanceof javafx.scene.text.Text node && node.getFill() instanceof Color colour
                ? colour : null;
    }

    /** The menu item's own text is what the reader sees; it has to use -color-text. */
    private static void assertMenuItemText(Parent popup, Color expected) {
        Label item = (Label) popup.lookup(".menu-item .label");
        assertThat(item).as("the first menu item label").isNotNull();
        assertThat(item.getTextFill()).as("menu item text in %s", popup.getStyleClass())
                .isEqualTo(expected);
    }

    private static Region region(Parent popup, String selector) {
        javafx.scene.Node node = popup.lookup(selector);
        Region region = node instanceof Region found ? found
                : popup instanceof Region root ? root : null;
        if (region != null) region.applyCss();
        return region;
    }

    private static Color background(javafx.scene.Node node) {
        if (!(node instanceof Region region)) return null;
        Background background = region.getBackground();
        if (background == null || background.getFills().isEmpty()) return null;
        BackgroundFill fill = background.getFills().getFirst();
        return fill.getFill() instanceof Color colour ? colour : null;
    }
}
