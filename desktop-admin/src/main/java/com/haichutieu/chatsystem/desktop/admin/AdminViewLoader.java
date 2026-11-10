package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/** Loads admin FXML views with explicitly constructed controllers and the app ResourceBundle. */
final class AdminViewLoader {
    private static final String BASE = "/com/haichutieu/chatsystem/desktop/admin/view/";
    static final String LOGIN = BASE + "login.fxml";
    static final String SHELL = BASE + "shell.fxml";
    static final String ACCOUNTS = BASE + "accounts.fxml";
    static final String REPORTS = BASE + "reports.fxml";
    static final String GROUPS = BASE + "groups.fxml";
    static final String REGISTRATIONS = BASE + "registrations.fxml";
    static final String ANALYTICS = BASE + "analytics.fxml";
    static final String AUDIT = BASE + "audit.fxml";

    private static final String STYLESHEET = Objects.requireNonNull(
            AdminViewLoader.class.getResource("admin.css"), "admin.css is missing").toExternalForm();

    private AdminViewLoader() {
    }

    static String stylesheetUrl() {
        return STYLESHEET;
    }

    static void applyStylesheet(Scene scene) {
        if (scene != null && !scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Parent> T load(String resource, Object controller, I18n i18n) {
        URL location = AdminViewLoader.class.getResource(resource);
        if (location == null) {
            throw new IllegalStateException("Missing FXML resource: " + resource);
        }
        FXMLLoader loader = new FXMLLoader(location, i18n.bundle());
        loader.setController(controller);
        try {
            return (T) loader.load();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load " + resource, exception);
        }
    }
}
