package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.model.MyProfile;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

/**
 * Thin launcher and composition root for the user desktop. Screens are FXML views with
 * controllers; the signed-in session lives in {@link UserAppContext}, never in a static field.
 */
public final class UserDesktopApplication extends Application {
    private static final String WINDOW_WIDTH = "user.window.width";
    private static final String WINDOW_HEIGHT = "user.window.height";
    private static final String WINDOW_X = "user.window.x";
    private static final String WINDOW_Y = "user.window.y";
    private final String originOverride;
    private final boolean realtimeEnabled;
    private final java.net.URI webSocketUriOverride;

    private Stage stage;
    private UserAppContext context;
    private ThemeManager themeManager;
    private I18n i18n;
    private final Preferences preferences = Preferences.userNodeForPackage(UserDesktopApplication.class);

    public UserDesktopApplication() {
        this(null, true, null);
    }

    /** Test seam: a loopback origin and optional realtime endpoint are used by JavaFX tests. */
    UserDesktopApplication(String originOverride, boolean realtimeEnabled) {
        this(originOverride, realtimeEnabled, null);
    }

    UserDesktopApplication(String originOverride, boolean realtimeEnabled,
                           java.net.URI webSocketUriOverride) {
        this.originOverride = originOverride;
        this.realtimeEnabled = realtimeEnabled;
        this.webSocketUriOverride = webSocketUriOverride;
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.themeManager = new ThemeManager();
        this.i18n = I18n.forUserApplication();
        this.context = new UserAppContext(stage, serverOrigin(), realtimeEnabled,
                webSocketUriOverride, themeManager, i18n);
        stage.setTitle(i18n.get("app.name"));
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        restoreWindowGeometry(stage);
        showLogin();
        stage.show();
        stage.setOnCloseRequest(event -> saveWindowGeometry());
    }

    private void showLogin() {
        showLogin(null);
    }

    private void showLogin(String message) {
        LoginController controller = new LoginController(context, this::showShell,
                this::showRegister, this::showPasswordReset, message);
        Parent root = ViewLoader.load(ViewLoader.LOGIN, controller, i18n);
        Scene scene = new Scene(root, 520, 640);
        themeManager.apply(scene);
        stage.setScene(scene);
    }

    private void showRegister() {
        RegisterController controller = new RegisterController(context,
                username -> showLogin(i18n.format("login.registered", username)), this::showLogin);
        Parent root = ViewLoader.load(ViewLoader.REGISTER, controller, i18n);
        Scene scene = new Scene(root, 560, 780);
        themeManager.apply(scene);
        stage.setScene(scene);
    }

    private void showPasswordReset() {
        PasswordResetController controller = new PasswordResetController(context,
                this::showLogin, this::showLogin);
        Parent root = ViewLoader.load(ViewLoader.PASSWORD_RESET, controller, i18n);
        Scene scene = new Scene(root, 540, 640);
        themeManager.apply(scene);
        stage.setScene(scene);
    }

    private void showShell(MyProfile profile) {
        ShellController controller = new ShellController(context, this::showLogin);
        Parent root = ViewLoader.load(ViewLoader.SHELL, controller, i18n);
        Scene scene = new Scene(root, 1180, 760);
        themeManager.apply(scene);
        stage.setScene(scene);
    }

    @Override
    public void stop() {
        saveWindowGeometry();
        if (context != null) context.close();
    }

    private void restoreWindowGeometry(Stage stage) {
        double width = preferences.getDouble(WINDOW_WIDTH, 1180);
        double height = preferences.getDouble(WINDOW_HEIGHT, 760);
        double x = preferences.getDouble(WINDOW_X, Double.NaN);
        double y = preferences.getDouble(WINDOW_Y, Double.NaN);
        stage.setWidth(Math.max(900, width));
        stage.setHeight(Math.max(600, height));
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            stage.setX(x);
            stage.setY(y);
        }
    }

    private void saveWindowGeometry() {
        if (stage == null) return;
        if (stage.isShowing()) {
            preferences.putDouble(WINDOW_WIDTH, stage.getWidth());
            preferences.putDouble(WINDOW_HEIGHT, stage.getHeight());
            preferences.putDouble(WINDOW_X, stage.getX());
            preferences.putDouble(WINDOW_Y, stage.getY());
        }
    }

    private String serverOrigin() {
        return originOverride != null ? originOverride
                : System.getenv().getOrDefault("NEXUS_SERVER_ORIGIN", "http://localhost:8080");
    }
}
