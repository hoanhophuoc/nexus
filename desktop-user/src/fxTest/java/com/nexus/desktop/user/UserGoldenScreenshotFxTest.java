package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.User;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden screenshots for the new user workspace. Goldens are generated with
 * {@code USER_UPDATE_GOLDENS=true} at 960x720 (account), 560x620 (report) and 640x600 (group),
 * then compared at 0.75% differing pixels with a per-channel delta of 16.
 * CI compares against {@code golden-ci}, generated on the hosted Linux runner, so
 * distribution-specific text rasterization does not require a loose tolerance.
 */
class UserGoldenScreenshotFxTest {
    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String GOLDEN_DIR = "ci".equalsIgnoreCase(System.getenv("GOLDEN_BASELINE"))
            ? "golden-ci" : "golden";
    private static final double MAX_RATIO = 0.0075;
    private static final int MAX_DELTA = 16;

    @Test
    void newUserScreensMatchGoldensInLightAndDark(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        TimeZone previousZone = TimeZone.getDefault();
        System.setProperty("user.home", home.toString());
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (FakeChatServer server = new FakeChatServer()) {
            Stage stage = onFx(Stage::new);
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n i18n = new I18n(BASE, Locale.ENGLISH);
            UserAppContext context = new UserAppContext(stage, server.origin().toString(), false,
                    null, theme, i18n);
            context.activate(context.signIn("alice", PASSWORD.toCharArray()));
            try {
                Parent account = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.ACCOUNT, new AccountController(context,
                                id -> { }, user -> { }, () -> { }, () -> { }), i18n),
                        960, 720));
                waitForFx(() -> account.lookup("#friendsList") instanceof javafx.scene.control.ListView<?> list
                        && !list.getItems().isEmpty(), Duration.ofSeconds(10));
                snapshot(stage, account, "account-light.png", "account-dark.png", theme);

                Parent register = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.REGISTER, new RegisterController(context,
                                username -> { }, () -> { }), i18n), 560, 780));
                snapshot(stage, register, "register-light.png", "register-dark.png", theme);

                Parent reset = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.PASSWORD_RESET, new PasswordResetController(context,
                                message -> { }, () -> { }), i18n), 540, 640));
                snapshot(stage, reset, "reset-light.png", "reset-dark.png", theme);

                User target = new User().id(FakeChatServer.FRIEND_ID).username("bob").fullName("Bob");
                CachedMessage evidence = new CachedMessage(UUID.fromString(
                        "44444444-4444-4444-8444-444444444444"), FakeChatServer.CONVERSATION_ID,
                        FakeChatServer.FRIEND_ID.toString(), "the reported message",
                        Instant.parse("2026-11-09T10:15:30Z"));
                Parent report = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.REPORT, new ReportController(context, target,
                                List.of(evidence), () -> { }), i18n), 560, 620));
                snapshot(stage, report, "report-light.png", "report-dark.png", theme);

                Conversation group = context.groups().createGroup("Team",
                        List.of(FakeChatServer.FRIEND_ID));
                Parent groupSettings = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.GROUP_SETTINGS, new GroupSettingsController(context,
                                group, () -> { }, () -> { }, () -> { }), i18n), 640, 600));
                waitForFx(() -> groupSettings.lookup("#membersList") instanceof javafx.scene.control.ListView<?> list
                        && list.getItems().size() == 2, Duration.ofSeconds(10));
                snapshot(stage, groupSettings, "group-light.png", "group-dark.png", theme);
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        } finally {
            TimeZone.setDefault(previousZone);
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

    private static void snapshot(Stage stage, Parent root, String light, String dark,
                                 ThemeManager theme) throws Exception {
        onFx(() -> {
            theme.setTheme(Theme.LIGHT);
            theme.apply(stage.getScene());
            clearFocus(root);
            root.applyCss();
            root.layout();
            return true;
        });
        assertGolden(onFx(() -> capture(stage)), light);
        onFx(() -> {
            theme.setTheme(Theme.DARK);
            theme.apply(stage.getScene());
            clearFocus(root);
            root.applyCss();
            root.layout();
            return true;
        });
        assertGolden(onFx(() -> capture(stage)), dark);
        onFx(() -> {
            theme.setTheme(Theme.LIGHT);
            theme.apply(stage.getScene());
            return true;
        });
    }

    private static void clearFocus(Parent root) {
        root.setFocusTraversable(true);
        root.requestFocus();
    }

    private static WritableImage capture(Stage stage) {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return stage.getScene().getRoot().snapshot(parameters, new WritableImage(
                (int) stage.getScene().getWidth(), (int) stage.getScene().getHeight()));
    }

    private static void assertGolden(WritableImage actual, String name) throws Exception {
        Path output = Path.of("build", "golden-actual").resolve(name);
        Files.createDirectories(output.getParent());
        ImageIO.write(fromFx(actual), "png", output.toFile());
        boolean update = "true".equalsIgnoreCase(System.getenv("USER_UPDATE_GOLDENS"));
        Path source = projectRoot().resolve("src/fxTest/resources").resolve(GOLDEN_DIR).resolve(name);
        if (update) {
            Files.createDirectories(source.getParent());
            Files.copy(output, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        BufferedImage golden;
        try (InputStream input = UserGoldenScreenshotFxTest.class.getResourceAsStream("/" + GOLDEN_DIR + "/" + name)) {
            assertThat(input).as("golden resource %s", name).isNotNull();
            golden = ImageIO.read(input);
        }
        assertThat(golden.getWidth()).isEqualTo((int) actual.getWidth());
        assertThat(golden.getHeight()).isEqualTo((int) actual.getHeight());
        BufferedImage actualImage = fromFx(actual);
        int different = 0;
        for (int y = 0; y < golden.getHeight(); y++) {
            for (int x = 0; x < golden.getWidth(); x++) {
                if (differs(golden.getRGB(x, y), actualImage.getRGB(x, y))) different++;
            }
        }
        double ratio = (double) different / (golden.getWidth() * (double) golden.getHeight());
        assertThat(ratio).as("golden mismatch ratio for %s (actual: %s)", name, output)
                .isLessThanOrEqualTo(MAX_RATIO);
    }

    private static boolean differs(int expected, int actual) {
        int alpha = Math.abs(((expected >> 24) & 0xff) - ((actual >> 24) & 0xff));
        int red = Math.abs(((expected >> 16) & 0xff) - ((actual >> 16) & 0xff));
        int green = Math.abs(((expected >> 8) & 0xff) - ((actual >> 8) & 0xff));
        int blue = Math.abs((expected & 0xff) - (actual & 0xff));
        return alpha > MAX_DELTA || red > MAX_DELTA || green > MAX_DELTA || blue > MAX_DELTA;
    }

    private static BufferedImage fromFx(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) buffered.setRGB(x, y, reader.getArgb(x, y));
        }
        return buffered;
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isDirectory(current.resolve("src/fxTest"))) return current;
        Path nested = current.resolve("desktop-user");
        if (Files.isDirectory(nested.resolve("src/fxTest"))) return nested;
        throw new IllegalStateException("Cannot locate desktop-user fxTest sources from " + current);
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The screen can still be filling its lists.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the golden screen.");
    }

    private static <T> T onFx(Callable<T> action) {
        startToolkit();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("JavaFX action failed", exception);
        }
    }

    private static synchronized void startToolkit() {
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }
}
