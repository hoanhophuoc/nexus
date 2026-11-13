package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The picker is the least covered screen in the user client: no flow test ever opened it, so its
 * configuration and its person cell were unexecuted. These tests drive the controller the way
 * {@link UserPickerController} is used — inject the FXML fields, initialise, configure, then render
 * one row — without needing a server or a window.
 */
class UserPickerControllerFxTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";

    @Test
    void configuresASingleChoicePickerAndSelectsTheFirstPerson() throws Exception {
        assertThat(onFx(() -> {
            I18n i18n = new I18n(BASE, Locale.ENGLISH);
            UserPickerController controller = controller(i18n);
            controller.configure("Start a chat", "Pick one person", false, "Name", List.of(
                    item("Hoa", true), item("Binh", false)), false);

            ListView<?> list = (ListView<?>) field(controller, "userList");
            return list.getItems().size() == 2
                    && ((Label) field(controller, "titleLabel")).getText().equals("Start a chat")
                    && ((Label) field(controller, "descriptionLabel")).getText().equals("Pick one person")
                    && !((TextField) field(controller, "nameField")).isVisible()
                    && list.getSelectionModel().getSelectionMode() == SelectionMode.SINGLE
                    && controller.selectedItems().size() == 1;
        })).isTrue();
    }

    @Test
    void configuresAMultipleChoicePickerAndKeepsTheNamePrompt() throws Exception {
        assertThat(onFx(() -> {
            I18n i18n = new I18n(BASE, Locale.ENGLISH);
            UserPickerController controller = controller(i18n);
            controller.configure("New group", "Pick people", true, "Group name", List.of(
                    item("Hoa", true), item("Binh", false), item("Chi", false)), true);

            ListView<?> list = (ListView<?>) field(controller, "userList");
            TextField nameField = (TextField) field(controller, "nameField");
            @SuppressWarnings("unchecked")
            ListView<UserPickerItem> people = (ListView<UserPickerItem>) list;
            people.getSelectionModel().selectRange(0, 2);
            return list.getSelectionModel().getSelectionMode() == SelectionMode.MULTIPLE
                    && nameField.isVisible()
                    && "Group name".equals(nameField.getPromptText())
                    && controller.selectedItems().size() == 2
                    && controller.name().isEmpty();
        })).isTrue();
    }

    @Test
    void trimsTheTypedNameAndTreatsAnUnsetFieldAsEmpty() throws Exception {
        assertThat(onFx(() -> {
            I18n i18n = new I18n(BASE, Locale.ENGLISH);
            UserPickerController controller = controller(i18n);
            controller.configure("New group", "Pick people", true, "Group name", List.of(), true);
            TextField nameField = (TextField) field(controller, "nameField");

            nameField.setText("  Team Hoa  ");
            String trimmed = controller.name();
            nameField.setText(null);
            String unset = controller.name();

            return "Team Hoa".equals(trimmed) && unset.isEmpty() && controller.selectedItems().isEmpty();
        })).isTrue();
    }

    @Test
    void rendersAPersonRowAndFallsBackToTheEmptyState() throws Exception {
        assertThat(onFx(() -> {
            I18n i18n = new I18n(BASE, Locale.ENGLISH);
            UserPickerController controller = controller(i18n);
            controller.configure("Start a chat", "Pick one person", false, "Name",
                    List.of(item("Hoa", true), item("Binh", false)), false);
            ListView<UserPickerItem> list = list(controller);
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(new StackPane(list), 360, 420));
                stage.show();
                list.applyCss();
                list.layout();

                boolean rendered = list.lookupAll(".list-cell").stream()
                        .filter(ListCell.class::isInstance)
                        .map(ListCell.class::cast)
                        .anyMatch(cell -> cell.getGraphic() != null);

                // an empty picker shows the placeholder the controller installed, not a blank list
                list.getItems().clear();
                list.applyCss();
                list.layout();
                boolean emptyState = list.lookup(".label") instanceof Label;

                return rendered && emptyState;
            } finally {
                stage.close();
            }
        })).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static ListView<UserPickerItem> list(UserPickerController controller)
            throws ReflectiveOperationException {
        return (ListView<UserPickerItem>) field(controller, "userList");
    }

    private static UserPickerController controller(I18n i18n) throws ReflectiveOperationException {
        UserPickerController controller = new UserPickerController(i18n);
        set(controller, "titleLabel", new Label());
        set(controller, "descriptionLabel", new Label());
        set(controller, "nameField", new TextField());
        set(controller, "userList", new ListView<UserPickerItem>());
        invoke(controller, "initialize");
        return controller;
    }

    private static UserPickerItem item(String title, boolean online) {
        return new UserPickerItem(UUID.randomUUID(), title, "subtitle of " + title, online);
    }

    private static Object field(UserPickerController controller, String name)
            throws ReflectiveOperationException {
        Field field = UserPickerController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void set(UserPickerController controller, String name, Object value)
            throws ReflectiveOperationException {
        Field field = UserPickerController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static void invoke(UserPickerController controller, String name)
            throws ReflectiveOperationException {
        var method = UserPickerController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
        } catch (IllegalStateException alreadyRunning) {
            // the toolkit is shared with the other JavaFX tests
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            // closing the stage of one test must not shut the shared toolkit down
            Platform.setImplicitExit(false);
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
