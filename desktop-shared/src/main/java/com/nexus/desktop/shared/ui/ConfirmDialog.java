package com.nexus.desktop.shared.ui;

import javafx.geometry.Insets;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * One confirmation surface for destructive actions. It uses the shared design tokens so the
 * user desktop and the admin desktop present the same blocking, keyboard-reachable decision.
 */
public final class ConfirmDialog {
    private ConfirmDialog() {
    }

    public static boolean show(Window owner, ThemeManager theme, String title, String message,
                               String confirmText, String cancelText, boolean dangerous) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        Node previousFocus = owner != null && owner.getScene() != null
                ? owner.getScene().getFocusOwner() : null;
        dialog.setTitle(title);
        dialog.setResizable(true);
        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        heading.setWrapText(true);
        Label body = new Label(message);
        body.getStyleClass().add("dialog-subtitle");
        body.setWrapText(true);
        body.setMaxWidth(460);
        VBox content = new VBox(10, heading, body);
        content.getStyleClass().addAll("dialog-content", "confirm-dialog");
        content.setPadding(new Insets(4, 2, 4, 2));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("confirm-dialog");
        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(cancelText, ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, cancel);
        dialog.getDialogPane().setAccessibleText(title);
        Node confirmButton = dialog.getDialogPane().lookupButton(confirm);
        if (confirmButton != null) {
            confirmButton.setId("confirmDialogConfirm");
            confirmButton.getStyleClass().add(dangerous ? "danger-button" : "primary-button");
        }
        Node cancelButton = dialog.getDialogPane().lookupButton(cancel);
        if (cancelButton != null) {
            cancelButton.setId("confirmDialogCancel");
            cancelButton.getStyleClass().add("ghost-button");
        }
        Node cancelFocus = cancelButton;
        dialog.setOnShown(event -> {
            if (cancelFocus != null) cancelFocus.requestFocus();
        });
        dialog.setOnHidden(event -> {
            Platform.runLater(() -> {
                if (owner != null) owner.requestFocus();
                if (previousFocus != null) previousFocus.requestFocus();
            });
        });
        if (theme != null) theme.apply(dialog.getDialogPane());
        Optional<ButtonType> choice = dialog.showAndWait();
        return choice.isPresent() && choice.get() == confirm;
    }
}
