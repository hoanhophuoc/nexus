package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Objects;

/** FXML controller for choosing people to chat with or manage in a group. */
public final class UserPickerController {
    private final I18n i18n;

    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private TextField nameField;
    @FXML private ListView<UserPickerItem> userList;

    UserPickerController(I18n i18n) {
        this.i18n = i18n;
    }

    @FXML
    private void initialize() {
        userList.setPlaceholder(UiComponents.emptyState(i18n.get("dialog.noCandidates"),
                i18n.get("dialog.noCandidatesHint"), null, null));
        nameField.setAccessibleText(i18n.get("dialog.groupName"));
        userList.setAccessibleText(i18n.get("dialog.noCandidates"));
        userList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(UserPickerItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                HBox row = UiComponents.personRow(
                        UiComponents.avatar(item.title(), 30),
                        item.title(),
                        item.subtitle(),
                        item.online(),
                        i18n.get("common.online"),
                        i18n.get("common.offline"));
                setGraphic(row);
                setText(null);
            }
        });
    }

    void configure(String title, String description, boolean askName, String namePrompt,
                   List<UserPickerItem> items, boolean multiple) {
        Objects.requireNonNull(titleLabel, "titleLabel").setText(title);
        descriptionLabel.setText(description);
        userList.setAccessibleText(title);
        nameField.setPromptText(namePrompt);
        nameField.setVisible(askName);
        nameField.setManaged(askName);
        userList.getItems().setAll(items);
        userList.getSelectionModel().setSelectionMode(multiple
                ? SelectionMode.MULTIPLE
                : SelectionMode.SINGLE);
        if (!items.isEmpty()) userList.getSelectionModel().selectFirst();
    }

    String name() {
        return nameField.getText() == null ? "" : nameField.getText().strip();
    }

    List<UserPickerItem> selectedItems() {
        return List.copyOf(userList.getSelectionModel().getSelectedItems());
    }
}
