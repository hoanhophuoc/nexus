package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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
import java.time.LocalDate;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import com.nexus.desktop.shared.testing.GoldenPointer;

import static com.nexus.desktop.shared.testing.FxThread.onFx;

/**
 * Golden screenshots for the two new admin workspaces. Goldens are generated with
 * {@code ADMIN_UPDATE_GOLDENS=true} and compared with a small reviewed pixel tolerance.
 * CI compares against {@code golden-ci}, generated on the hosted Linux runner, so
 * distribution-specific text rasterization does not require a loose tolerance.
 */
class AdminGoldenScreenshotFxTest {
    private static final String BASE = "com.nexus.desktop.admin.i18n.messages";
    private static final int WIDTH = 1180;
    private static final int HEIGHT = 780;
    private static final String GOLDEN_DIR = "ci".equalsIgnoreCase(System.getenv("GOLDEN_BASELINE"))
            ? "golden-ci" : "golden";
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
            // The sign-in is three HTTP round trips: done on the test thread, so the FX task below
            // only builds the scene it is being measured on.
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n english = new I18n(BASE, Locale.ENGLISH);
            AdminAppContext context = onFx(() -> {
                Stage ownStage = new Stage();
                stageReference.set(ownStage);
                return new AdminAppContext(ownStage, server.origin().toString(), theme, english);
            });
            context.activate(context.signIn("admin", "correct horse battery staple".toCharArray()));
            onFx(() -> {
                Stage stage = stageReference.get();
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
                // The login history and friends panes fill in behind the table, so the wait covers
                // their counts too: a capture taken while they still read "Loading…" is a different
                // screen from one taken after, and which one you get depends on the machine's speed.
                waitForFx(() -> tableRows(stage, 0) > 1 && tableRows(stage, 1) > 0 && tableRows(stage, 2) > 0
                                && !loadingDetails(stage, english),
                        Duration.ofSeconds(10));
                onFx(() -> {
                    rootReference.get().applyCss();
                    rootReference.get().layout();
                    clearFocus(rootReference.get());
                    Label title = (Label) rootReference.get().lookup(".title");
                    assertThat(title).as("accounts title").isNotNull();
                    assertThat(title.getFont().getFamily()).as("bundled UI font")
                            .isEqualTo("Nexus Sans");
                    return true;
                });
                GoldenPointer.capture(rootReference.get(), () -> {
                    selectPrimaryRow(stage, english, "#accountsTable");
                    assertGolden(snapshot(stage), "accounts-light.png");
                });

                onFx(() -> {
                    themeReference.get().setTheme(Theme.DARK);
                    themeReference.get().apply(stage.getScene());
                    rootReference.get().applyCss();
                    rootReference.get().layout();
                    return true;
                });
                GoldenPointer.capture(rootReference.get(), () -> {
                    selectPrimaryRow(stage, english, "#accountsTable");
                    assertGolden(snapshot(stage), "accounts-dark.png");
                });

                AtomicReference<Parent> reportsRoot = new AtomicReference<>();
                onFx(() -> {
                    themeReference.get().setTheme(Theme.LIGHT);
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
                GoldenPointer.capture(reportsRoot.get(), () -> {
                    selectPrimaryRow(stage, english, "#reportsTable");
                    assertGolden(snapshot(stage), "reports-light.png");
                });

                onFx(() -> {
                    themeReference.get().setTheme(Theme.DARK);
                    themeReference.get().apply(stage.getScene());
                    reportsRoot.get().applyCss();
                    reportsRoot.get().layout();
                    return true;
                });
                GoldenPointer.capture(reportsRoot.get(), () -> {
                    selectPrimaryRow(stage, english, "#reportsTable");
                    assertGolden(snapshot(stage), "reports-dark.png");
                });
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

    /**
     * The two screens an administrator meets first, which had no baseline: sign-in and the shell with
     * its navigation, identity block and the accounts workspace it opens on.
     */
    @Test
    void loginAndShellMatchGoldensInLightAndDark() throws Exception {
        TimeZone previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (FakeAdminServer server = new FakeAdminServer()) {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n english = new I18n(BASE, Locale.ENGLISH);
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AdminAppContext context = onFx(() -> {
                Stage ownStage = new Stage();
                stageReference.set(ownStage);
                return new AdminAppContext(ownStage, server.origin().toString(), theme, english);
            });
            Stage stage = stageReference.get();
            try {
                Parent login = onFx(() -> {
                    Parent root = AdminViewLoader.load(AdminViewLoader.LOGIN,
                            new AdminLoginController(context, profile -> { }), english);
                    mount(stage, theme, root);
                    return root;
                });
                GoldenPointer.capture(login,
                        () -> assertGolden(snapshot(stage), "login-light.png"));
                onFx(() -> {
                    theme.setTheme(Theme.DARK);
                    theme.apply(stage.getScene());
                    login.applyCss();
                    login.layout();
                    return true;
                });
                GoldenPointer.capture(login,
                        () -> assertGolden(snapshot(stage), "login-dark.png"));

                // The sign-in is three HTTP round trips: done on the test thread, like the existing
                // account/report goldens, so the FX side only builds the scene being measured.
                context.activate(context.signIn("admin", "correct horse battery staple".toCharArray()));
                AtomicReference<Parent> shellReference = new AtomicReference<>();
                onFx(() -> {
                    theme.setTheme(Theme.LIGHT);
                    Parent root = AdminViewLoader.load(AdminViewLoader.SHELL,
                            new AdminShellController(context, () -> { }), english);
                    mount(stage, theme, root);
                    shellReference.set(root);
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 1 && !loadingDetails(stage, english),
                        Duration.ofSeconds(10));
                onFx(() -> {
                    shellReference.get().applyCss();
                    shellReference.get().layout();
                    clearFocus(shellReference.get());
                    return true;
                });
                GoldenPointer.capture(shellReference.get(), () -> {
                    selectPrimaryRow(stage, english, "#accountsTable");
                    assertGolden(snapshot(stage), "shell-light.png");
                });
                onFx(() -> {
                    theme.setTheme(Theme.DARK);
                    theme.apply(stage.getScene());
                    shellReference.get().applyCss();
                    shellReference.get().layout();
                    return true;
                });
                GoldenPointer.capture(shellReference.get(), () -> {
                    selectPrimaryRow(stage, english, "#accountsTable");
                    assertGolden(snapshot(stage), "shell-dark.png");
                });
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    /**
     * The administrator screens in Vietnamese. The translated toolbar, table headers and detail
     * titles are the longest strings the layout has to hold, so they get their own baseline rather
     * than being assumed safe because the English one fits.
     */
    @Test
    void vietnameseAdminScreensMatchGoldensInLightAndDark() throws Exception {
        TimeZone previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (FakeAdminServer server = new FakeAdminServer()) {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AdminAppContext context = onFx(() -> {
                Stage ownStage = new Stage();
                stageReference.set(ownStage);
                return new AdminAppContext(ownStage, server.origin().toString(), theme, vietnamese);
            });
            Stage stage = stageReference.get();
            try {
                Parent login = onFx(() -> {
                    Parent root = AdminViewLoader.load(AdminViewLoader.LOGIN,
                            new AdminLoginController(context, profile -> { }), vietnamese);
                    mount(stage, theme, root);
                    return root;
                });
                GoldenPointer.capture(login,
                        () -> assertGolden(snapshot(stage), "login-vi-light.png"));
                onFx(() -> {
                    theme.setTheme(Theme.DARK);
                    theme.apply(stage.getScene());
                    login.applyCss();
                    login.layout();
                    return true;
                });
                GoldenPointer.capture(login,
                        () -> assertGolden(snapshot(stage), "login-vi-dark.png"));

                context.activate(context.signIn("admin", "correct horse battery staple".toCharArray()));
                AtomicReference<Parent> shellReference = new AtomicReference<>();
                onFx(() -> {
                    theme.setTheme(Theme.LIGHT);
                    Parent root = AdminViewLoader.load(AdminViewLoader.SHELL,
                            new AdminShellController(context, () -> { }), vietnamese);
                    mount(stage, theme, root);
                    shellReference.set(root);
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 1 && !loadingDetails(stage, vietnamese),
                        Duration.ofSeconds(10));
                onFx(() -> {
                    shellReference.get().applyCss();
                    shellReference.get().layout();
                    clearFocus(shellReference.get());
                    return true;
                });
                GoldenPointer.capture(shellReference.get(), () -> {
                    selectPrimaryRow(stage, vietnamese, "#accountsTable");
                    assertGolden(snapshot(stage), "shell-vi-light.png");
                });
                onFx(() -> {
                    theme.setTheme(Theme.DARK);
                    theme.apply(stage.getScene());
                    shellReference.get().applyCss();
                    shellReference.get().layout();
                    return true;
                });
                GoldenPointer.capture(shellReference.get(), () -> {
                    selectPrimaryRow(stage, vietnamese, "#accountsTable");
                    assertGolden(snapshot(stage), "shell-vi-dark.png");
                });

                AtomicReference<Parent> reportsRoot = new AtomicReference<>();
                onFx(() -> {
                    theme.setTheme(Theme.LIGHT);
                    Parent reports = AdminViewLoader.load(AdminViewLoader.REPORTS,
                            new ReportsController(context), vietnamese);
                    Scene scene = new Scene(reports, WIDTH, HEIGHT);
                    theme.apply(scene);
                    AdminViewLoader.applyStylesheet(scene);
                    stage.setScene(scene);
                    stage.show();
                    reports.applyCss();
                    reports.layout();
                    clearFocus(reports);
                    reportsRoot.set(reports);
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 0 && tableRows(stage, 1) > 0,
                        Duration.ofSeconds(10));
                GoldenPointer.capture(reportsRoot.get(), () -> {
                    selectPrimaryRow(stage, vietnamese, "#reportsTable");
                    assertGolden(snapshot(stage), "reports-vi-light.png");
                });
                onFx(() -> {
                    theme.setTheme(Theme.DARK);
                    theme.apply(stage.getScene());
                    reportsRoot.get().applyCss();
                    reportsRoot.get().layout();
                    return true;
                });
                GoldenPointer.capture(reportsRoot.get(), () -> {
                    selectPrimaryRow(stage, vietnamese, "#reportsTable");
                    assertGolden(snapshot(stage), "reports-vi-dark.png");
                });
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    /**
     * The four admin workspaces that had no baseline: analytics, audit, groups and registrations.
     * Both date-bounded screens open on today's date, so the test pins their date fields before the
     * capture — a baseline that moved with the calendar would fail the next day's run.
     */
    @Test
    void analyticsAuditGroupsAndRegistrationsMatchGoldensInLightAndDark() throws Exception {
        TimeZone previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (FakeAdminServer server = new FakeAdminServer()) {
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n english = new I18n(BASE, Locale.ENGLISH);
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AdminAppContext context = onFx(() -> {
                Stage ownStage = new Stage();
                stageReference.set(ownStage);
                return new AdminAppContext(ownStage, server.origin().toString(), theme, english);
            });
            context.activate(context.signIn("admin", "correct horse battery staple".toCharArray()));
            Stage stage = stageReference.get();
            try {
                Parent analytics = onFx(() -> AdminViewLoader.load(AdminViewLoader.ANALYTICS,
                        new AnalyticsController(context), english));
                // Tall enough for the charts, the activity report and the friend report at once:
                // analytics is the one admin screen taller than the working window, and a baseline
                // taken at 780 would record a scroll position instead of the screen.
                onFx(() -> {
                    mount(stage, theme, analytics, WIDTH, 1120);
                    return true;
                });
                onFx(() -> {
                    ((TextField) analytics.lookup("#yearField")).setText("2026");
                    ((DatePicker) analytics.lookup("#activityFrom")).setValue(LocalDate.of(2026, 11, 1));
                    ((DatePicker) analytics.lookup("#activityTo")).setValue(LocalDate.of(2026, 11, 30));
                    ((Button) analytics.lookup("#loadChartsButton")).fire();
                    ((Button) analytics.lookup("#loadActivityButton")).fire();
                    // Neither report table loads on open: each has its own button.
                    ((Button) analytics.lookup("#loadFriendsButton")).fire();
                    return true;
                });
                // Both charts come from one task that writes its status last, and the two report
                // tables load on their own; all three are waited for so the capture is the loaded
                // screen rather than whichever half happened to land first.
                waitForFx(() -> tableRows(stage, 0) > 0 && tableRows(stage, 1) > 0
                                && labelContaining(stage, english.get("admin.analytics.chartsLoaded")),
                        Duration.ofSeconds(10));
                captureLightAndDark(stage, theme, analytics, "analytics");

                Parent audit = onFx(() -> AdminViewLoader.load(AdminViewLoader.AUDIT,
                        new AuditController(context), english));
                onFx(() -> {
                    mount(stage, theme, audit);
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 0, Duration.ofSeconds(10));
                captureLightAndDark(stage, theme, audit, "audit");

                Parent groups = onFx(() -> AdminViewLoader.load(AdminViewLoader.GROUPS,
                        new GroupsController(context), english));
                onFx(() -> {
                    mount(stage, theme, groups);
                    return true;
                });
                // The member roster fills in only after the first group is selected, so both tables
                // have to have rows before the capture.
                waitForFx(() -> tableRows(stage, 0) > 0 && tableRows(stage, 1) > 0, Duration.ofSeconds(10));
                captureLightAndDark(stage, theme, groups, "groups");

                Parent registrations = onFx(() -> AdminViewLoader.load(AdminViewLoader.REGISTRATIONS,
                        new RegistrationsController(context), english));
                onFx(() -> {
                    mount(stage, theme, registrations);
                    return true;
                });
                onFx(() -> {
                    ((DatePicker) registrations.lookup("#fromDate")).setValue(LocalDate.of(2026, 11, 1));
                    ((DatePicker) registrations.lookup("#toDate")).setValue(LocalDate.of(2026, 11, 30));
                    ((Button) registrations.lookup("#applyButton")).fire();
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 0, Duration.ofSeconds(10));
                captureLightAndDark(stage, theme, registrations, "registrations");
            } finally {
                onFx(() -> {
                    stage.close();
                    context.close();
                    return true;
                });
            }
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    /** Captures one mounted screen in both themes, and leaves the manager back on LIGHT. */
    private static void captureLightAndDark(Stage stage, ThemeManager theme, Parent root, String name)
            throws Exception {
        for (Theme requested : new Theme[]{Theme.LIGHT, Theme.DARK}) {
            onFx(() -> {
                theme.setTheme(requested);
                theme.apply(stage.getScene());
                clearFocus(root);
                root.applyCss();
                root.layout();
                return true;
            });
            String suffix = requested == Theme.DARK ? "dark" : "light";
            GoldenPointer.capture(root,
                    () -> assertGolden(snapshot(stage), name + "-" + suffix + ".png"));
        }
        onFx(() -> {
            theme.setTheme(Theme.LIGHT);
            theme.apply(stage.getScene());
            return true;
        });
    }

    private static void mount(Stage stage, ThemeManager theme, Parent root) {
        mount(stage, theme, root, WIDTH, HEIGHT);
    }

    private static void mount(Stage stage, ThemeManager theme, Parent root, int width, int height) {
        Scene scene = new Scene(root, width, height);
        theme.apply(scene);
        AdminViewLoader.applyStylesheet(scene);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        clearFocus(root);
    }

    /** True while a detail pane still shows its placeholder instead of the loaded count. */
    private static boolean loadingDetails(Stage stage, I18n i18n) {
        return labelContaining(stage, i18n.get("admin.accounts.loginHistoryLoading"))
                || labelContaining(stage, i18n.get("admin.accounts.friendListLoading"));
    }

    /**
     * The capture clears table selections so no runner's focus behaviour decides them; this restores
     * the one the screen is about — the record whose detail the right-hand pane shows.
     */
    private static void selectPrimaryRow(Stage stage, I18n i18n, String tableId) {
        onFx(() -> {
            if (stage.getScene().getRoot().lookup(tableId) instanceof TableView<?> primary
                    && !primary.getItems().isEmpty()) {
                primary.getSelectionModel().select(0);
                primary.applyCss();
                primary.layout();
            }
            return true;
        });
        // Selecting the row reloads its detail panes, so wait again: the capture must not record the
        // placeholder the reload puts back.
        waitForFx(() -> !loadingDetails(stage, i18n), Duration.ofSeconds(10));
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
        Path source = projectRoot().resolve("src/fxTest/resources").resolve(GOLDEN_DIR).resolve(name);
        if (update) {
            Files.createDirectories(source.getParent());
            ImageIO.write(actualImage, "png", source.toFile());
            return;
        }
        ImageIO.write(actualImage, "png", output.toFile());
        BufferedImage golden;
        try (InputStream input = AdminGoldenScreenshotFxTest.class.getResourceAsStream("/" + GOLDEN_DIR + "/" + name)) {
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

}
