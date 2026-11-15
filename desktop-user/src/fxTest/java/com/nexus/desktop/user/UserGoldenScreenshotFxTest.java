package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.User;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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
import static com.nexus.desktop.shared.testing.FxThread.onFx;
import com.nexus.desktop.shared.testing.GoldenPointer;

import static com.nexus.desktop.shared.testing.FxThread.waitForFx;

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
                snapshot(stage, account, "account-light.png", "account-dark.png", theme, null, i18n);

                Parent register = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.REGISTER, new RegisterController(context,
                                username -> { }, () -> { }), i18n), 560, 780));
                snapshot(stage, register, "register-light.png", "register-dark.png", theme, null, i18n);

                Parent reset = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.PASSWORD_RESET, new PasswordResetController(context,
                                message -> { }, () -> { }), i18n), 540, 640));
                snapshot(stage, reset, "reset-light.png", "reset-dark.png", theme, null, i18n);

                User target = new User().id(FakeChatServer.FRIEND_ID).username("bob").fullName("Bob");
                CachedMessage evidence = new CachedMessage(UUID.fromString(
                        "44444444-4444-4444-8444-444444444444"), FakeChatServer.CONVERSATION_ID,
                        FakeChatServer.FRIEND_ID, "", "the reported message",
                        Instant.parse("2026-11-09T10:15:30Z"));
                Parent report = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.REPORT, new ReportController(context, target,
                                List.of(evidence), () -> { }), i18n), 560, 620));
                snapshot(stage, report, "report-light.png", "report-dark.png", theme, null, i18n);

                Conversation group = context.groups().createGroup("Team",
                        List.of(FakeChatServer.FRIEND_ID));
                Parent groupSettings = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.GROUP_SETTINGS, new GroupSettingsController(context,
                                group, () -> { }, () -> { }, () -> { }), i18n), 640, 600));
                waitForFx(() -> groupSettings.lookup("#membersList") instanceof javafx.scene.control.ListView<?> list
                        && list.getItems().size() == 2, Duration.ofSeconds(10));
                snapshot(stage, groupSettings, "group-light.png", "group-dark.png", theme, null, i18n);
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

    /**
     * The two screens a user meets first, which had no baseline: sign-in and the chat shell with a
     * settled conversation list, one incoming message and the connected status.
     */
    @Test
    void loginAndShellMatchGoldensInLightAndDark(@TempDir Path home) throws Exception {
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
            try {
                Parent login = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.LOGIN, new LoginController(context, profile -> { }),
                                i18n), 520, 640));
                snapshot(stage, login, "login-light.png", "login-dark.png", theme, null, i18n);

                context.activate(context.signIn("alice", PASSWORD.toCharArray()));
                Parent shell = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.SHELL, new ShellController(context, () -> { }), i18n),
                        1180, 760));
                // Settled means the shell has its conversation, its one message bubble and the status
                // the load task writes last; snapshotting earlier would baseline a loading screen.
                waitForFx(() -> shell.lookupAll(".message-bubble").size() == 1
                        && shell.lookup("#statusLabel") instanceof javafx.scene.control.Label status
                        && i18n.get("shell.connected").equals(status.getText()),
                        Duration.ofSeconds(10));
                snapshot(stage, shell, "shell-light.png", "shell-dark.png", theme,
                        FakeChatServer.FRIEND_NAME, i18n);
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

    /**
     * The group workspace, which had no baseline: a member message carrying its resolved name, an own
     * message, and the header where only group actions apply.
     */
    @Test
    void groupChatMatchesGoldensInLightAndDark(@TempDir Path home) throws Exception {
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
            Conversation group = context.groups().createGroup("Team", List.of(FakeChatServer.FRIEND_ID));
            // Seeded history keeps the capture about rendering: one member message whose name the sync
            // resolved, and one of the user's own.
            context.cache().upsert(new CachedMessage(UUID.randomUUID(), group.getId(),
                    FakeChatServer.FRIEND_ID, "Bob", "hello group",
                    Instant.parse("2026-11-10T09:30:00Z")));
            context.cache().upsert(new CachedMessage(UUID.randomUUID(), group.getId(),
                    FakeChatServer.USER_ID, "", "my group message",
                    Instant.parse("2026-11-10T09:31:00Z")));
            try {
                Parent shell = onFx(() -> mount(stage, theme,
                        ViewLoader.load(ViewLoader.SHELL, new ShellController(context, () -> { }), i18n),
                        1180, 760));
                waitForFx(() -> openConversationNamed(shell, "Team"), Duration.ofSeconds(10));
                waitForFx(() -> shell.lookupAll(".message-bubble").size() == 2
                        && shell.lookup(".chat-title") instanceof Label title
                        && "Team".equals(title.getText()), Duration.ofSeconds(10));
                snapshot(stage, shell, "group-chat-light.png", "group-chat-dark.png", theme,
                        "Team", i18n);
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

    /** Selects a conversation by name once the shell's own refresh has listed it. */
    private static boolean openConversationNamed(Parent root, String name) {
        for (Node node : root.lookupAll(".list-view")) {
            if (!(node instanceof ListView<?> list)) continue;
            int index = conversationIndex(list, name);
            if (index >= 0) {
                list.getSelectionModel().clearAndSelect(index);
                return true;
            }
        }
        return false;
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
                                 ThemeManager theme, String openConversation, I18n i18n) throws Exception {
        onFx(() -> {
            theme.setTheme(Theme.LIGHT);
            theme.apply(stage.getScene());
            clearFocus(root);
            root.applyCss();
            root.layout();
            return true;
        });
        GoldenPointer.capture(root, () -> {
            pinLists(root, openConversation, i18n);
            assertGolden(onFx(() -> capture(stage)), light);
        });
        onFx(() -> {
            theme.setTheme(Theme.DARK);
            theme.apply(stage.getScene());
            clearFocus(root);
            root.applyCss();
            root.layout();
            return true;
        });
        GoldenPointer.capture(root, () -> {
            pinLists(root, openConversation, i18n);
            assertGolden(onFx(() -> capture(stage)), dark);
        });
        onFx(() -> {
            theme.setTheme(Theme.LIGHT);
            theme.apply(stage.getScene());
            return true;
        });
    }

    /**
     * Pins the conversation list to the chat the screen shows and empties the lists that are not the
     * screen's subject. Selecting a row that is already selected raises no change event, so this never
     * re-opens the conversation or reloads its history; only a runner that highlighted a different row
     * makes the selection move, and then the wait covers the reload it causes.
     */
    private static void pinLists(Parent root, String openConversation, I18n i18n) throws Exception {
        boolean reselected = Boolean.TRUE.equals(onFx(() -> {
            boolean moved = false;
            for (Node node : root.lookupAll(".list-view")) {
                if (!(node instanceof ListView<?> list)) continue;
                if ("conversationList".equals(list.getId())) {
                    if (openConversation == null) continue;
                    int index = conversationIndex(list, openConversation);
                    if (index >= 0 && list.getSelectionModel().getSelectedIndex() != index) {
                        list.getSelectionModel().select(index);
                        moved = true;
                    }
                } else if (!list.getSelectionModel().isEmpty()) {
                    list.getSelectionModel().clearSelection();
                }
            }
            return moved;
        }));
        if (!reselected) return;
        // Moving the selection re-opens the conversation, so wait for the chat it belongs to and for
        // its messages to arrive; the connection status alone says nothing about that load.
        waitForFx(() -> root.lookup(".chat-title") instanceof Label title
                        && openConversation.equals(title.getText())
                        && !labelContaining(root, i18n.get("shell.loadingMessages")),
                Duration.ofSeconds(10));
    }

    private static boolean labelContaining(Parent root, String text) {
        return root.lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static int conversationIndex(ListView<?> list, String name) {
        for (int index = 0; index < list.getItems().size(); index++) {
            if (list.getItems().get(index) instanceof Conversation conversation
                    && name.equals(conversation.getName())) {
                return index;
            }
        }
        return -1;
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

}
