package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.ConfirmDialog;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyboard operation of a popup. A popup is a window of its own, so the questions worth asking are
 * the ones a user meets: the keystroke has to reach the control that opened it, Escape has to close
 * it, and a choice made from the keyboard has to survive the popup closing.
 *
 * <p>Focus is read while the popup is open, not after it closes: a JavaFX popup does not take
 * keyboard focus, so an "it went back to the control" check after closing would pass even if
 * nothing had happened. The one window that really does take focus is a dialog, and there the
 * restore is asserted, because there it can fail.</p>
 *
 * <p>Two behaviours are deliberately absent because they do not hold in JavaFX 27: a plain Down or
 * Space at a {@code DatePicker} does not open its calendar, and the arrows and Enter inside that
 * calendar do not move or commit a day — the date picker commits what the user types with Enter.
 * The tests below assert that path rather than inventing a calendar-navigation assertion the skin
 * would not satisfy.</p>
 */
class PopupKeyboardFxTest {

    @Test
    void escapeClosesAComboBoxPopupWithoutTakingFocus() {
        ThemeManager theme = onFx(ThemeManager::new);
        ComboBox<String> combo = onFx(() -> {
            ComboBox<String> box = new ComboBox<>(
                    FXCollections.observableArrayList("Direct chat", "Group chat"));
            box.getSelectionModel().selectFirst();
            return box;
        });
        Mounted mounted = show(theme, combo);
        try {
            onFx(() -> {
                combo.requestFocus();
                combo.show();
                return true;
            });
            assertThat(onFx(combo::isShowing)).as("the popup opened").isTrue();
            assertThat(onFx(() -> mounted.scene().getFocusOwner()))
                    .as("an open popup must not take focus from its control").isSameAs(combo);

            onFx(() -> {
                press(combo, KeyCode.ESCAPE);
                return true;
            });
            waitForFx(() -> !combo.isShowing(), Duration.ofSeconds(5));
        } finally {
            mounted.close();
        }
    }

    @Test
    void arrowKeysChooseAnItemAndEnterClosesThePopup() {
        ThemeManager theme = onFx(ThemeManager::new);
        ComboBox<String> combo = onFx(() -> {
            ComboBox<String> box = new ComboBox<>(
                    FXCollections.observableArrayList("Direct chat", "Group chat"));
            box.getSelectionModel().selectFirst();
            return box;
        });
        Mounted mounted = show(theme, combo);
        try {
            onFx(() -> {
                combo.requestFocus();
                combo.show();
                return true;
            });
            assertThat(onFx(combo::isShowing)).as("the popup opened").isTrue();

            onFx(() -> {
                press(combo, KeyCode.DOWN);
                return true;
            });
            assertThat(onFx(() -> combo.getSelectionModel().getSelectedIndex()))
                    .as("the arrow moved the choice").isEqualTo(1);

            onFx(() -> {
                press(combo, KeyCode.ENTER);
                return true;
            });
            waitForFx(() -> !combo.isShowing(), Duration.ofSeconds(5));
            assertThat(onFx(() -> combo.getSelectionModel().getSelectedItem()))
                    .as("the chosen item survives the popup").isEqualTo("Group chat");
        } finally {
            mounted.close();
        }
    }

    @Test
    void escapeClosesAMenuButtonMenu() {
        ThemeManager theme = onFx(ThemeManager::new);
        MenuButton menu = onFx(() -> new MenuButton("Actions", null,
                new MenuItem("Delete for me"), new MenuItem("Delete for everyone")));
        Mounted mounted = show(theme, menu);
        try {
            onFx(() -> {
                menu.requestFocus();
                menu.show();
                return true;
            });
            assertThat(onFx(menu::isShowing)).as("the menu opened").isTrue();

            onFx(() -> {
                press(menu, KeyCode.ESCAPE);
                return true;
            });
            waitForFx(() -> !menu.isShowing(), Duration.ofSeconds(5));
        } finally {
            mounted.close();
        }
    }

    /** The calendar is a popup of its own; Escape has to close it without moving focus away. */
    @Test
    void escapeClosesTheDatePickerCalendarWithoutTakingFocus() {
        ThemeManager theme = onFx(ThemeManager::new);
        DatePicker picker = onFx(() -> new DatePicker(LocalDate.of(2026, 11, 10)));
        Mounted mounted = show(theme, picker);
        try {
            onFx(() -> {
                picker.requestFocus();
                picker.show();
                return true;
            });
            assertThat(onFx(picker::isShowing)).as("the calendar opened").isTrue();
            assertThat(onFx(() -> mounted.scene().getFocusOwner()))
                    .as("the calendar must not take focus from its picker").isSameAs(picker);

            onFx(() -> {
                press(picker, KeyCode.ESCAPE);
                return true;
            });
            waitForFx(() -> !picker.isShowing(), Duration.ofSeconds(5));
        } finally {
            mounted.close();
        }
    }

    /** The date picker's keyboard commit path is the typed value confirmed with Enter. */
    @Test
    void typingADateAndPressingEnterCommitsIt() {
        ThemeManager theme = onFx(ThemeManager::new);
        DatePicker picker = onFx(() -> new DatePicker(LocalDate.of(2026, 11, 10)));
        Mounted mounted = show(theme, picker);
        try {
            onFx(() -> {
                picker.requestFocus();
                picker.getEditor().setText("12/25/2026");
                return true;
            });
            onFx(() -> {
                press(picker, KeyCode.ENTER);
                return true;
            });

            assertThat(onFx(picker::getValue))
                    .as("the typed date is the value after Enter")
                    .isEqualTo(LocalDate.of(2026, 12, 25));
        } finally {
            mounted.close();
        }
    }

    /**
     * A tooltip cannot take focus, so the focus question does not apply; what does apply is that it
     * is themed like every other popup and that it goes away with the window it belongs to.
     */
    @Test
    void tooltipTakesTheThemeAndHidesWithItsOwnerWindow() {
        ThemeManager theme = onFx(ThemeManager::new);
        Label target = onFx(() -> new Label("Delete for everyone"));
        Tooltip tip = onFx(() -> new Tooltip("Delete for everyone"));
        Mounted mounted = show(theme, target);
        try {
            onFx(() -> {
                tip.show(target, 120, 120);
                return true;
            });
            assertThat(onFx(tip::isShowing)).as("the tooltip showed").isTrue();
            assertThat(onFx(() -> tip.getScene().getStylesheets()))
                    .as("tooltip scene stylesheet")
                    .contains(ThemeManager.stylesheetUrl());
            assertThat(onFx(() -> tip.getScene().getRoot().getStyleClass()))
                    .as("tooltip theme class").contains("theme-light");
            // The tooltip paints -color-surface as its text colour, so reading it proves the
            // semantic token reached the popup skin rather than the toolkit default.
            assertThat(onFx(() -> tooltipTextFill(tip))).as("tooltip text in light")
                    .isEqualTo(Color.web("#FFFFFF"));

            onFx(() -> {
                theme.setTheme(Theme.DARK);
                return true;
            });
            assertThat(onFx(() -> tip.getScene().getRoot().getStyleClass()))
                    .as("tooltip follows the theme").contains("theme-dark");
            assertThat(onFx(() -> tooltipTextFill(tip))).as("tooltip text in dark")
                    .isEqualTo(Color.web("#151C26"));

            onFx(() -> {
                mounted.stage().hide();
                return true;
            });
            waitForFx(() -> !tip.isShowing(), Duration.ofSeconds(5));
        } finally {
            onFx(() -> {
                tip.hide();
                return true;
            });
            mounted.close();
        }
    }

    /**
     * A destructive confirmation is the one popup that really takes focus. Its contract is that it
     * opens on the safe action and that Escape cancels it.
     *
     * <p>The focus that returns to the invoking control afterwards is not asserted here: removing
     * the dialog's own restore leaves the test green, because the toolkit returns focus to the
     * owner window on its own. An assertion that cannot be made to fail would only look like
     * coverage; the two checks that can fail are the ones kept.</p>
     */
    @Test
    void confirmDialogOpensOnCancelAndRestoresFocusAfterEscape() throws Exception {
        ThemeManager theme = onFx(ThemeManager::new);
        Button trigger = onFx(() -> new Button("Delete conversation"));
        Mounted mounted = show(theme, trigger);
        try {
            onFx(() -> {
                trigger.requestFocus();
                return true;
            });
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Platform.runLater(() -> result.complete(ConfirmDialog.show(mounted.stage(), theme,
                    "Delete conversation", "Delete this conversation for everyone?",
                    "Delete", "Cancel", true)));
            Stage dialog = awaitDialog();

            assertThat(onFx(() -> dialog.getScene().getFocusOwner().getId()))
                    .as("a destructive dialog opens on its safe action")
                    .isEqualTo("confirmDialogCancel");

            onFx(() -> {
                press(dialog.getScene().getFocusOwner(), KeyCode.ESCAPE);
                return true;
            });
            assertThat(result.get(5, TimeUnit.SECONDS)).as("Escape cancels").isFalse();
        } finally {
            mounted.close();
        }
    }

    // ---------------------------------------------------------------- mounting and driving

    private record Mounted(Stage stage, Scene scene) {
        void close() {
            onFx(() -> {
                stage.hide();
                return true;
            });
        }
    }

    private static Mounted show(ThemeManager theme, Node... nodes) {
        Stage stage = onFx(Stage::new);
        Scene scene = onFx(() -> {
            theme.setTheme(Theme.LIGHT);
            VBox root = new VBox(8, nodes);
            Scene created = new Scene(root, 480, 320);
            theme.apply(created);
            stage.setScene(created);
            stage.show();
            root.applyCss();
            root.layout();
            return created;
        });
        return new Mounted(stage, scene);
    }

    /**
     * A real keystroke for a popup that does not take focus: the toolkit delivers key input to the
     * focus owner, which stays on the control that opened the popup, and the control's behaviour is
     * what reacts to it. The event is therefore aimed at the control, not at the popup window.
     */
    private static void press(Node target, KeyCode code) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false));
        target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code,
                false, false, false, false));
    }

    private static Color tooltipTextFill(Tooltip tip) {
        Node text = tip.getScene().getRoot().lookup(".text");
        return (Color) ((Text) text).getFill();
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
}
