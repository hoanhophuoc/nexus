package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.net.URL;

/** Loads an FXML view with an explicitly constructed controller and the app's ResourceBundle. */
final class ViewLoader {
    static final String LOGIN = "/com/nexus/desktop/user/view/login.fxml";
    static final String SHELL = "/com/nexus/desktop/user/view/shell.fxml";
    static final String USER_PICKER = "/com/nexus/desktop/user/view/user-picker.fxml";
    static final String REGISTER = "/com/nexus/desktop/user/view/register.fxml";
    static final String PASSWORD_RESET = "/com/nexus/desktop/user/view/password-reset.fxml";
    static final String ACCOUNT = "/com/nexus/desktop/user/view/account.fxml";
    static final String GROUP_SETTINGS = "/com/nexus/desktop/user/view/group-settings.fxml";
    static final String REPORT = "/com/nexus/desktop/user/view/report.fxml";

    private ViewLoader() {
    }

    @SuppressWarnings("unchecked")
    static <T extends Parent> T load(String resource, Object controller, I18n i18n) {
        URL location = ViewLoader.class.getResource(resource);
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
