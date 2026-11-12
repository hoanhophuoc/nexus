package com.nexus.desktop.shared.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.Locale;

/** Reusable, theme-aware building blocks shared by the user and admin desktops. */
public final class UiComponents {
    private UiComponents() {
    }

    public static StackPane avatar(String name, double size) {
        Label initials = new Label(initials(name));
        initials.getStyleClass().add("avatar-initials");
        StackPane avatar = new StackPane(initials);
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(size, size);
        avatar.setPrefSize(size, size);
        avatar.setMaxSize(size, size);
        avatar.setAccessibleText(name);
        return avatar;
    }

    public static Circle presenceDot(boolean online, String accessibleText) {
        Circle dot = new Circle(4);
        dot.getStyleClass().addAll("presence-dot", online ? "online" : "offline");
        dot.setAccessibleText(accessibleText);
        return dot;
    }

    public static HBox personRow(Node avatar, String title, String subtitle, boolean online,
                                 String onlineText, String offlineText) {
        Label name = new Label(title);
        name.getStyleClass().add("conversation-name");
        Label detail = new Label(subtitle);
        detail.getStyleClass().add("conversation-meta");
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, avatar, text, spacer(),
                presenceDot(online, online ? onlineText : offlineText));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("person-row");
        return row;
    }

    public static VBox emptyState(String title, String message, String actionText, Runnable action) {
        VBox box = new VBox(8);
        box.getStyleClass().add("state-view");
        box.setAlignment(Pos.CENTER);
        Label heading = new Label(title);
        heading.getStyleClass().add("state-title");
        heading.setWrapText(true);
        Label body = new Label(message);
        body.getStyleClass().add("state-message");
        body.setWrapText(true);
        body.setMaxWidth(420);
        box.getChildren().addAll(heading, body);
        if (actionText != null && action != null) {
            Button button = new Button(actionText);
            button.getStyleClass().add("secondary-button");
            button.setOnAction(event -> action.run());
            box.getChildren().add(button);
        }
        return box;
    }

    public static VBox loadingState(String message) {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(28, 28);
        indicator.setMaxSize(28, 28);
        Label label = new Label(message);
        label.getStyleClass().add("state-message");
        VBox box = new VBox(10, indicator, label);
        box.getStyleClass().add("state-view");
        box.setAlignment(Pos.CENTER);
        return box;
    }

    public static VBox errorState(String title, String message, String retryText, Runnable retry) {
        VBox box = emptyState(title, message, retryText, retry);
        box.getStyleClass().add("error-state");
        return box;
    }

    public static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    static String initials(String name) {
        if (name == null) return "?";
        String stripped = name.strip();
        if (stripped.isEmpty()) return "?";
        String[] parts = stripped.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "?";
        String first = parts[0].substring(0, 1);
        String last = "";
        if (parts.length > 1) {
            String tail = parts[parts.length - 1];
            last = tail.isEmpty() ? "" : tail.substring(0, 1);
        }
        return (first + last).toUpperCase(Locale.ROOT);
    }
}
