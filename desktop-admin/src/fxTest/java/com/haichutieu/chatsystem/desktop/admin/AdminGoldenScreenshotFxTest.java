package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.Theme;
import com.haichutieu.chatsystem.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.SnapshotParameters;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden screenshots for the two new admin workspaces. Goldens are generated with
 * {@code ADMIN_UPDATE_GOLDENS=true} and compared with a small reviewed pixel tolerance.
 */
class AdminGoldenScreenshotFxTest {
    private static final String BASE = "com.haichutieu.chatsystem.desktop.admin.i18n.messages";
    private static final int WIDTH = 1180;
    private static final int HEIGHT = 780;
    private static final double MAX_DIFFERENT_PIXEL_RATIO = 0.0075;
    private static final int MAX_CHANNEL_DELTA = 16;

    @Test
    void accountsAndReportsMatchGoldensInLightAndDark() throws Exception {
        TimeZone previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (FakeAdminServer server = new FakeAdminServer()) {
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<AdminAppContext> contextReference = new AtomicReference<>();
            AtomicReference<ThemeManager> themeReference = new AtomicReference<>();
            AtomicReference<Parent> rootReference = new AtomicReference<>();
            onFx(() -> {
                Stage stage = new Stage();
                ThemeManager theme = new ThemeManager();
                theme.setTheme(Theme.LIGHT);
                I18n english = new I18n(BASE, Locale.ENGLISH);
                AdminAppContext context = new AdminAppContext(stage, server.origin().toString(), theme, english);
                context.activate(context.signIn("admin", "correct horse battery staple".toCharArray()));
                Parent root = AdminViewLoader.load(AdminViewLoader.ACCOUNTS,
                        new AccountsController(context), english);
                Scene scene = new Scene(root, WIDTH, HEIGHT);
                theme.apply(scene);
                AdminViewLoader.applyStylesheet(scene);
                stage.setScene(scene);
                stage.show();
                stageReference.set(stage);
                contextReference.set(context);
                themeReference.set(theme);
                rootReference.set(root);
                return true;
            });
            Stage stage = stageReference.get();
            try {
                waitForFx(() -> tableRows(stage, 0) > 1 && tableRows(stage, 1) > 0 && tableRows(stage, 2) > 0,
                        Duration.ofSeconds(10));
                onFx(() -> {
                    rootReference.get().applyCss();
                    rootReference.get().layout();
                    clearFocus(rootReference.get());
                    return true;
                });
                assertGolden(snapshot(stage), "accounts-light.png");

                onFx(() -> {
                    themeReference.get().setTheme(Theme.DARK);
                    themeReference.get().apply(stage.getScene());
                    rootReference.get().applyCss();
                    rootReference.get().layout();
                    return true;
                });
                assertGolden(snapshot(stage), "accounts-dark.png");

                AtomicReference<Parent> reportsRoot = new AtomicReference<>();
                onFx(() -> {
                    themeReference.get().setTheme(Theme.LIGHT);
                    I18n english = new I18n(BASE, Locale.ENGLISH);
                    Parent reports = AdminViewLoader.load(AdminViewLoader.REPORTS,
                            new ReportsController(contextReference.get()), english);
                    Scene scene = new Scene(reports, WIDTH, HEIGHT);
                    themeReference.get().apply(scene);
                    AdminViewLoader.applyStylesheet(scene);
                    stage.setScene(scene);
                    stage.show();
                    rootReference.set(reports);
                    reportsRoot.set(reports);
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 0 && tableRows(stage, 1) > 0
                                && labelContaining(stage, "Evidence for mallory"),
                        Duration.ofSeconds(10));
                onFx(() -> {
                    reportsRoot.get().applyCss();
                    reportsRoot.get().layout();
                    clearFocus(reportsRoot.get());
                    return true;
                });
                assertGolden(snapshot(stage), "reports-light.png");

                onFx(() -> {
                    themeReference.get().setTheme(Theme.DARK);
                    themeReference.get().apply(stage.getScene());
                    reportsRoot.get().applyCss();
                    reportsRoot.get().layout();
                    return true;
                });
                assertGolden(snapshot(stage), "reports-dark.png");
            } finally {
                onFx(() -> {
                    stage.close();
                    contextReference.get().close();
                    return true;
                });
            }
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    private static WritableImage snapshot(Stage stage) {
        return onFx(() -> stage.getScene().getRoot().snapshot(new SnapshotParameters(), null));
    }

    private static void clearFocus(Parent root) {
        root.setFocusTraversable(true);
        root.requestFocus();
    }

    private static void assertGolden(WritableImage actual, String name) throws Exception {
        Path output = Path.of("build", "golden-actual").resolve(name);
        Files.createDirectories(output.getParent());
        BufferedImage actualImage = toBufferedImage(actual);
        boolean update = "true".equalsIgnoreCase(System.getenv("ADMIN_UPDATE_GOLDENS"));
        Path source = projectRoot().resolve("src/fxTest/resources/golden").resolve(name);
        if (update) {
            Files.createDirectories(source.getParent());
            ImageIO.write(actualImage, "png", source.toFile());
            return;
        }
        ImageIO.write(actualImage, "png", output.toFile());
        BufferedImage golden;
        try (InputStream input = AdminGoldenScreenshotFxTest.class.getResourceAsStream("/golden/" + name)) {
            assertThat(input).as("golden resource %s", name).isNotNull();
            golden = ImageIO.read(input);
        }
        assertThat(golden.getWidth()).as("golden width for %s", name).isEqualTo(actualImage.getWidth());
        assertThat(golden.getHeight()).as("golden height for %s", name).isEqualTo(actualImage.getHeight());
        long different = 0;
        for (int y = 0; y < actualImage.getHeight(); y++) {
            for (int x = 0; x < actualImage.getWidth(); x++) {
                if (differs(golden.getRGB(x, y), actualImage.getRGB(x, y))) different++;
            }
        }
        double ratio = (double) different / (actualImage.getWidth() * actualImage.getHeight());
        assertThat(ratio).as("golden mismatch ratio for %s (actual: %s)", name, output)
                .isLessThanOrEqualTo(MAX_DIFFERENT_PIXEL_RATIO);
    }

    private static boolean differs(int expected, int actual) {
        int alpha = Math.abs(((expected >>> 24) & 0xff) - ((actual >>> 24) & 0xff));
        int red = Math.abs(((expected >>> 16) & 0xff) - ((actual >>> 16) & 0xff));
        int green = Math.abs(((expected >>> 8) & 0xff) - ((actual >>> 8) & 0xff));
        int blue = Math.abs((expected & 0xff) - (actual & 0xff));
        return Math.max(Math.max(alpha, red), Math.max(green, blue)) > MAX_CHANNEL_DELTA;
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return result;
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isDirectory(current.resolve("src/fxTest"))) return current;
        Path nested = current.resolve("desktop-admin");
        if (Files.isDirectory(nested.resolve("src/fxTest"))) return nested;
        throw new IllegalStateException("Cannot locate desktop-admin fxTest sources from " + current);
    }

    @SuppressWarnings("rawtypes")
    private static int tableRows(Stage stage, int index) {
        TableView<?> table = table(stage, index);
        return table == null ? 0 : table.getItems().size();
    }

    @SuppressWarnings("rawtypes")
    private static TableView<?> table(Stage stage, int index) {
        if (stage.getScene() == null) return null;
        java.util.List<TableView> tables = stage.getScene().getRoot().lookupAll(".table-view").stream()
                .filter(TableView.class::isInstance).map(TableView.class::cast).toList();
        return index < tables.size() ? tables.get(index) : null;
    }

    private static boolean labelContaining(Stage stage, String text) {
        if (stage.getScene() == null) return false;
        return stage.getScene().getRoot().lookupAll(".label").stream()
                .filter(javafx.scene.control.Label.class::isInstance)
                .map(javafx.scene.control.Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The scene can still be swapping views.
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for JavaFX.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the admin scene.");
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
                throw new IllegalStateException("JavaFX toolkit did not start within 30 seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }
}
