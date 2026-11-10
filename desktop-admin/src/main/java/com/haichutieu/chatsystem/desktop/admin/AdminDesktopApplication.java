package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.ThemeManager;
import com.haichutieu.chatsystem.sdk.model.MyProfile;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Thin launcher and composition root for the admin desktop. The screens are FXML views; the
 * signed-in session lives in {@link AdminAppContext}, never in a static field.
 */
public final class AdminDesktopApplication extends Application {
    private final String originOverride;
    private Stage stage;
    private AdminAppContext context;
    private ThemeManager themeManager;
    private I18n i18n;

    public AdminDesktopApplication() {
        this(null);
    }

    /** Test seam: a loopback admin API origin. */
    AdminDesktopApplication(String originOverride) {
        this.originOverride = originOverride;
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.themeManager = new ThemeManager();
        this.i18n = I18n.forApplication("com.haichutieu.chatsystem.desktop.admin.i18n.messages");
        this.context = new AdminAppContext(stage, serverOrigin(), themeManager, i18n);
        stage.setTitle(i18n.get("admin.app.name"));
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        showLogin();
        stage.show();
    }

    private void showLogin() {
        AdminLoginController controller = new AdminLoginController(context, this::showShell);
        Parent root = AdminViewLoader.load(AdminViewLoader.LOGIN, controller, i18n);
        Scene scene = new Scene(root, 520, 640);
        themeManager.apply(scene);
        AdminViewLoader.applyStylesheet(scene);
        stage.setScene(scene);
    }

    private void showShell(MyProfile profile) {
        AdminShellController controller = new AdminShellController(context, this::showLogin);
        Parent root = AdminViewLoader.load(AdminViewLoader.SHELL, controller, i18n);
        Scene scene = new Scene(root, 1180, 780);
        themeManager.apply(scene);
        AdminViewLoader.applyStylesheet(scene);
        stage.setScene(scene);
    }

    @Override
    public void stop() {
        if (context != null) context.close();
    }

    private String serverOrigin() {
        return originOverride != null ? originOverride
                : System.getenv().getOrDefault("CHAT_SERVER_ORIGIN", "http://localhost:8080");
    }
}
