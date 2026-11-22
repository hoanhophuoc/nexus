package com.nexus.desktop.user;

import com.nexus.desktop.shared.SessionApi;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.model.Registration;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the user client against a real server: it registers its own account through
 * the API, signs in through the same code path the client uses, then renders the shell and moves it
 * between themes. It is the one check that proves the packaged client's stack — SDK, session cookie,
 * encrypted cache and FXML shell — works against a running service, without clicking anything.
 *
 * <pre>
 * NEXUS_REAL_SERVER=true ./gradlew :desktop-user:fxTest --tests '*RealServerUserSmokeFxTest'
 * </pre>
 *
 * <p>{@code scripts/smoke-desktop.sh} starts the database, Redis and the server, runs this, and tears
 * them down. The origin defaults to {@code http://127.0.0.1:8080} and can be set with
 * {@code NEXUS_REAL_SERVER_ORIGIN}.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_REAL_SERVER", matches = "true")
class RealServerUserSmokeFxTest {
    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void signsInAndRendersTheShellAgainstARealServer(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        String origin = env("NEXUS_REAL_SERVER_ORIGIN", "http://127.0.0.1:8080");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "smoke_" + suffix;
        try {
            System.out.println("SMOKE registering " + username + " at " + origin);
            SessionApi registration = new SessionApi(URI.create(origin));
            registration.api().getCsrfToken();
            registration.api().register(new Registration()
                    .username(username)
                    .fullName("Smoke Client")
                    .email(username + "@example.test")
                    .address("1 Smoke Road")
                    .birthDate(LocalDate.of(1990, 1, 1))
                    .gender(Registration.GenderEnum.OTHER)
                    .password(PASSWORD));

            Stage stage = onFx(Stage::new);
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n i18n = new I18n(BASE, Locale.ENGLISH);
            UserAppContext context = new UserAppContext(stage, origin, false, null, theme, i18n);
            try {
                System.out.println("SMOKE signing in");
                context.activate(context.signIn(username, PASSWORD.toCharArray()));

                Parent shell = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.SHELL, new ShellController(context, () -> { }), i18n),
                        1180, 760));
                // Connected is written last by the shell's load, so waiting for it means the server
                // round trip, the profile and the conversation list all finished.
                waitForFx(() -> shell.lookup("#statusLabel") instanceof Label status
                        && i18n.get("shell.connected").equals(status.getText()),
                        Duration.ofSeconds(30));
                assertThat(onFx(() -> shell.lookupAll(".list-view")))
                        .as("the conversation list renders").isNotEmpty();
                System.out.println("SMOKE shell connected");

                onFx(() -> {
                    theme.setTheme(Theme.DARK);
                    return true;
                });
                waitForFx(() -> shell.getStyleClass().contains("theme-dark"), Duration.ofSeconds(10));
                assertThat(theme.isDark()).as("the client reports the dark theme").isTrue();

                onFx(() -> {
                    theme.setTheme(Theme.LIGHT);
                    return true;
                });
                waitForFx(() -> shell.getStyleClass().contains("theme-light"), Duration.ofSeconds(10));
                System.out.println("SMOKE theme switched light -> dark -> light");
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static Parent mount(Stage stage, ThemeManager theme, Parent root, int width, int height) {
        Scene scene = new Scene(root, width, height);
        theme.apply(scene);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return root;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
