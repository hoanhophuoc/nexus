package com.nexus.spike;

import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Notification;
import atlantafx.base.controls.SegmentedControl;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.feather.FeatherIkonHandler;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AtlantaFX 3.0.0 + Ikonli 12.4.0 proof of concept (docs/ui-ux-tooling.md).
 *
 * <p>This is a check, not a demo: every claim the decision record asks for is asserted, and any
 * failed assertion leaves a non-zero exit status. It renders the same control sheet twice — once with
 * the stock Primer theme as the JavaFX user-agent stylesheet, once with the real Nexus
 * {@code ThemeManager} and {@code theme.css} on top — and records whether the tokens win the cascade,
 * whether the bundled font survives, whether the contrast holds, whether ComboBox and DatePicker
 * popups are skinned, whether a FontIcon loads from FXML, whether {@code admin.css} layers over the
 * tokens, whether the AtlantaFX extras the design contract would use build and skin, and whether one
 * ThemeManager still owns SYSTEM/LIGHT/DARK and remembers the choice.
 *
 * <p>What it deliberately does not claim: the packaged {@code jpackage} image (that needs the icon
 * font in production first) and the AtlantaFX-only screens are still open, and are recorded as open in
 * the decision record.
 *
 * <p>Run it with {@code xvfb-run -a ./gradlew :ui-spike:spikeCheck}; screenshots land in
 * {@code ui-spike/build/ui-spike/}.
 */
public final class UiSpike extends Application {
    private static final Path ROOT = repositoryRoot();
    private static final Path SHARED_UI = ROOT.resolve(
            "desktop-shared/src/main/resources/com/nexus/desktop/shared/ui");
    private static final Path ADMIN_CSS = ROOT.resolve(
            "desktop-admin/src/main/resources/com/nexus/desktop/admin/admin.css");
    private static final Path OUTPUT = Path.of(System.getProperty("uiSpike.output", "build/ui-spike"));
    private static final String NEXUS_SANS = "Nexus Sans";
    private static final double MIN_CONTRAST = 4.5;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final Map<String, Color> STOCK_SURFACES = new HashMap<>();

    private interface Check {
        void run() throws Exception;
    }

    @Override
    public void start(Stage stage) {
        try {
            Files.createDirectories(OUTPUT);
        } catch (Exception exception) {
            FAILURES.add("cannot create " + OUTPUT + ": " + exception);
        }
        check("the bundled Nexus fonts load and keep their family", UiSpike::fonts);
        for (Theme theme : List.of(new PrimerLight(), new PrimerDark())) {
            String variant = theme.isDarkMode() ? "dark" : "light";
            check(variant + ": the stock theme renders the control sheet",
                    () -> stockSheet(stage, theme, variant));
            check(variant + ": the Nexus tokens win the cascade over AtlantaFX",
                    () -> nexusSheet(stage, theme, variant));
        }
        check("the ComboBox and DatePicker popups are skinned", UiSpike::popups);
        check("a FontIcon loads from FXML with its font", UiSpike::fxmlIcon);
        check("admin.css layers over the shared tokens", UiSpike::adminCss);
        check("the AtlantaFX extras build and skin next to the tokens", UiSpike::atlantaControls);
        check("one ThemeManager owns SYSTEM/LIGHT/DARK and remembers the choice",
                UiSpike::themeOwnership);
        Platform.exit();
    }

    private static void check(String name, Check body) {
        try {
            body.run();
            System.out.printf("[spike] ok    %s%n", name);
        } catch (Throwable failure) {
            FAILURES.add(name + " — " + failure);
            System.out.printf("[spike] FAIL  %s: %s%n", name, failure.getMessage());
            failure.printStackTrace(System.out);
        }
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException(what);
    }

    private static void fonts() throws Exception {
        for (String resource : List.of("fonts/nexusSans-Regular.otf", "fonts/nexusSans-Bold.otf")) {
            try (InputStream stream = Files.newInputStream(SHARED_UI.resolve(resource))) {
                require(Font.loadFont(stream, 13) != null, resource + " did not load");
            }
        }
        require(Font.getFamilies().contains(NEXUS_SANS),
                NEXUS_SANS + " is not among the loaded families");
    }

    private static void stockSheet(Stage stage, Theme theme, String variant) throws Exception {
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        Parent root = sheet();
        Scene scene = new Scene(root, 780, 960);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        writePng(capture(scene), OUTPUT.resolve("primer-" + variant + ".png"));
        TextField field = (TextField) root.lookup("#spikeField");
        require(field != null, "the probe field is missing");
        Color stock = colour(backgroundOf(field));
        require(stock != null, "the stock theme left the field without a background");
        STOCK_SURFACES.put(variant, stock);
        System.out.printf("[spike]   %s stock field surface=%s%n", variant, stock);
    }

    private static void nexusSheet(Stage stage, Theme theme, String variant) throws Exception {
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        ThemeManager manager = new ThemeManager();
        manager.setTheme(theme.isDarkMode()
                ? com.nexus.desktop.shared.ui.Theme.DARK
                : com.nexus.desktop.shared.ui.Theme.LIGHT);
        Parent root = sheet();
        Scene scene = new Scene(root, 780, 960);
        manager.apply(scene);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        writePng(capture(scene), OUTPUT.resolve("nexus-on-primer-" + variant + ".png"));

        List<String> sheets = scene.getStylesheets();
        require(!sheets.isEmpty() && sheets.getLast().equals(ThemeManager.stylesheetUrl()),
                "theme.css is not the last application stylesheet: " + sheets);
        require(root.getStyleClass().contains(theme.isDarkMode() ? "theme-dark" : "theme-light"),
                "the theme class is missing from the root: " + root.getStyleClass());

        TextField field = (TextField) root.lookup("#spikeField");
        Label title = (Label) root.lookup("#spikeTitle");
        require(field != null && title != null, "the probe controls are missing");
        Color surface = colour(backgroundOf(field));
        require(surface != null, "Nexus left the field without a background");
        require(!surface.equals(STOCK_SURFACES.get(variant)),
                "the Nexus tokens did not change the field surface: " + surface);
        require(NEXUS_SANS.equals(title.getFont().getFamily()),
                "the bundled font is not applied: " + title.getFont().getFamily());

        double bodyContrast = contrast(title.getTextFill(), colour(backgroundOf((Region) root)));
        double fieldContrast = contrast(textFill(field), surface);
        require(bodyContrast >= MIN_CONTRAST,
                "title contrast is only " + round(bodyContrast) + ":1");
        require(fieldContrast >= MIN_CONTRAST,
                "field text contrast is only " + round(fieldContrast) + ":1");
        System.out.printf("[spike]   %s nexus field surface=%s body-contrast=%.2f "
                        + "field-contrast=%.2f%n", variant, surface, bodyContrast, fieldContrast);
    }

    private static void popups() {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        Stage stage = new Stage();
        ComboBox<String> combo = new ComboBox<>(
                FXCollections.observableArrayList("Direct chat", "Group chat"));
        combo.getSelectionModel().selectFirst();
        DatePicker date = new DatePicker(LocalDate.of(2026, 11, 15));
        VBox root = new VBox(10, combo, date);
        root.setPadding(new Insets(16));
        Scene scene = new Scene(root, 420, 260);
        stage.setScene(scene);
        stage.show();
        try {
            root.applyCss();
            root.layout();

            combo.show();
            Parent comboPopup = popupRoot();
            require(comboPopup != null, "the ComboBox opened no popup");
            comboPopup.applyCss();
            comboPopup.layout();
            Node list = comboPopup.lookup(".list-view");
            require(list instanceof Region, "the ComboBox popup has no skinned list");
            require(backgroundOf((Region) list) != null,
                    "the ComboBox popup list has no background");
            System.out.printf("[spike]   combo popup stylesheets=%s%n",
                    comboPopup.getScene().getStylesheets().stream()
                            .map(url -> url.substring(url.lastIndexOf('/') + 1)).toList());
            combo.hide();

            date.show();
            Parent datePopup = popupRoot();
            require(datePopup != null, "the DatePicker opened no popup");
            datePopup.applyCss();
            datePopup.layout();
            require(!datePopup.lookupAll(".label").isEmpty(),
                    "the DatePicker popup rendered no label");
            date.hide();

            require(combo.getSkin() != null && date.getSkin() != null,
                    "a popup control lost its skin");
        } finally {
            stage.close();
        }
    }

    private static void fxmlIcon() throws Exception {
        URL sheet = UiSpike.class.getResource("icon-sheet.fxml");
        require(sheet != null, "icon-sheet.fxml is missing from the module resources");
        FXMLLoader loader = new FXMLLoader(sheet);
        Parent root = loader.load();
        List<FontIcon> icons = root.lookupAll(".ikonli-font-icon").stream()
                .filter(FontIcon.class::isInstance).map(FontIcon.class::cast).toList();
        require(icons.size() == 3, "FXML built " + icons.size() + " icons instead of 3");
        // The pack's own font file has to load: a closed glyph set or a missing resource would leave
        // every icon rendering a fallback box while the node still existed.
        FeatherIkonHandler handler = new FeatherIkonHandler();
        require(handler.getFontResource() != null, "the Feather pack ships no font file");
        Font packFont;
        try (InputStream stream = handler.getFontResourceAsStream()) {
            packFont = Font.loadFont(stream, 16);
        }
        require(packFont != null, "the Feather font file did not load");
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 320, 80));
        stage.show();
        try {
            root.applyCss();
            root.layout();
            for (FontIcon icon : icons) {
                require(icon.getIconCode() != null, "an icon code did not resolve");
                String family = icon.getFont() == null ? "" : icon.getFont().getFamily();
                require(!family.isBlank(), "the icon has no font");
                require(icon.getBoundsInLocal().getWidth() > 0, "an icon rendered no width");
            }
            System.out.printf("[spike]   fxml icons=%s family=%s pack-family=%s%n",
                    icons.stream().map(icon -> icon.getIconCode().getDescription()).toList(),
                    icons.getFirst().getFont().getFamily(), packFont.getFamily());
        } finally {
            stage.close();
        }
    }

    private static void adminCss() {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        Region sidebar = new Region();
        sidebar.getStyleClass().add("admin-sidebar");
        Label identity = new Label("Signed in");
        identity.getStyleClass().add("admin-identity-label");
        VBox root = new VBox(8, sidebar, identity);
        root.getStyleClass().add("admin-shell");
        Scene scene = new Scene(root, 320, 220);
        ThemeManager manager = new ThemeManager();
        manager.setTheme(com.nexus.desktop.shared.ui.Theme.LIGHT);
        manager.apply(scene);
        scene.getStylesheets().add(ADMIN_CSS.toUri().toString());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        try {
            root.applyCss();
            root.layout();
            Color surface = colour(backgroundOf(sidebar));
            require(Color.web("#FFFFFF").equals(surface),
                    "admin.css did not resolve -color-surface: " + surface);
            require(identity.getTextFill() instanceof Color,
                    "admin.css left the identity label without a colour");
            System.out.printf("[spike]   admin sidebar=%s label=%s%n", surface,
                    identity.getTextFill());
        } finally {
            stage.close();
        }
    }

    private static void atlantaControls() {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        SegmentedControl segmented = new SegmentedControl("Direct", "Group");
        ToggleSwitch toggle = new ToggleSwitch("Notifications");
        Notification notification = new Notification("Security key synced",
                new FontIcon(Feather.SHIELD));
        ModalPane modal = new ModalPane();
        VBox box = new VBox(10, segmented, toggle, notification, modal);
        box.setPadding(new Insets(16));
        Scene scene = new Scene(box, 560, 420);
        ThemeManager manager = new ThemeManager();
        manager.setTheme(com.nexus.desktop.shared.ui.Theme.LIGHT);
        manager.apply(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        try {
            box.applyCss();
            box.layout();
            require(segmented.getSkin() != null, "SegmentedControl has no skin");
            require(toggle.getSkin() != null, "ToggleSwitch has no skin");
            require(notification.getSkin() != null, "Notification has no skin");
            require(modal.getSkin() != null, "ModalPane has no skin");
            require(segmented.getBoundsInParent().getWidth() > 0,
                    "SegmentedControl rendered no width");
            System.out.printf("[spike]   atlanta segmented=%.0fx%.0f toggle=%s%n",
                    segmented.getBoundsInParent().getWidth(),
                    segmented.getBoundsInParent().getHeight(),
                    toggle.getSkin().getClass().getSimpleName());
        } finally {
            stage.close();
        }
    }

    private static void themeOwnership() {
        ThemeManager first = new ThemeManager();
        first.setTheme(com.nexus.desktop.shared.ui.Theme.DARK);
        require(first.effectiveTheme() == com.nexus.desktop.shared.ui.Theme.DARK,
                "DARK was not effective");
        require(first.isDark(), "isDark disagreed with DARK");

        ThemeManager reloaded = new ThemeManager();
        require(reloaded.theme() == com.nexus.desktop.shared.ui.Theme.DARK,
                "the stored preference was not read back: " + reloaded.theme());

        reloaded.setTheme(com.nexus.desktop.shared.ui.Theme.LIGHT);
        require(!reloaded.isDark(), "LIGHT was not effective");

        reloaded.setTheme(com.nexus.desktop.shared.ui.Theme.SYSTEM);
        var expected = Platform.getPreferences().getColorScheme() == ColorScheme.DARK
                ? com.nexus.desktop.shared.ui.Theme.DARK
                : com.nexus.desktop.shared.ui.Theme.LIGHT;
        require(reloaded.effectiveTheme() == expected,
                "SYSTEM resolved to " + reloaded.effectiveTheme() + " instead of " + expected);
        // Leaves the isolated store on a neutral value rather than DARK.
        reloaded.setTheme(com.nexus.desktop.shared.ui.Theme.LIGHT);
        System.out.printf("[spike]   theme preference round-trip ok (system=%s)%n", expected);
    }

    private static Parent sheet() {
        Button primary = new Button("Primary");
        primary.getStyleClass().add("primary-button");
        Button secondary = new Button("Secondary");
        secondary.getStyleClass().add("secondary-button");
        Button ghost = new Button("Ghost");
        ghost.getStyleClass().add("ghost-button");
        Button danger = new Button("Delete");
        danger.getStyleClass().add("danger-button");
        Button disabled = new Button("Disabled");
        disabled.setDisable(true);
        Button icon = new Button(null, new FontIcon(Feather.MOON));
        icon.getStyleClass().add("icon-button");
        icon.setAccessibleText("Switch to dark theme");

        TextField field = new TextField("hello");
        field.setId("spikeField");
        field.setPromptText("Write a message");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        ComboBox<String> combo = new ComboBox<>(
                FXCollections.observableArrayList("Direct chat", "Group chat"));
        combo.getSelectionModel().selectFirst();
        DatePicker date = new DatePicker(LocalDate.of(2026, 11, 15));
        CheckBox check = new CheckBox("Lock the account on uphold");
        check.setSelected(true);
        MenuButton menu = new MenuButton("Actions", null,
                new MenuItem("For me"), new MenuItem("For everyone"));
        ListView<String> list = new ListView<>(FXCollections.observableArrayList(
                "Alice", "Bob", "Charlie"));
        list.getSelectionModel().select(1);
        list.setPrefHeight(110);
        TableView<String> table = new TableView<>();
        TableColumn<String, String> column = new TableColumn<>("Account");
        column.setCellValueFactory(data ->
                new javafx.beans.property.ReadOnlyStringWrapper(data.getValue()));
        table.getColumns().add(column);
        table.getItems().addAll("alice", "bob");
        table.setPrefHeight(110);

        Label status = new Label("Connected");
        status.getStyleClass().add("caption");
        Label title = new Label("Nexus control sheet");
        title.setId("spikeTitle");
        title.getStyleClass().add("title");

        FlowPane icons = new FlowPane(10, 10,
                new FontIcon(Feather.MOON), new FontIcon(Feather.SUN),
                new FontIcon(Feather.MESSAGE_SQUARE), new FontIcon(Feather.USERS),
                new FontIcon(Feather.SHIELD), new FontIcon(Feather.SEND));

        VBox body = new VBox(12, title, status,
                new FlowPane(8, 8, primary, secondary, ghost, danger, disabled, icon,
                        new ProgressIndicator()),
                field, password, combo, date, check, menu, icons, list, table);
        body.setPadding(new Insets(20));
        return body;
    }

    private static Parent popupRoot() {
        for (Window window : Window.getWindows()) {
            if (!(window instanceof PopupWindow) || !window.isShowing()) continue;
            Scene scene = window.getScene();
            if (scene != null && scene.getRoot() != null) return scene.getRoot();
        }
        return null;
    }

    private static Paint backgroundOf(Region region) {
        if (region == null) return null;
        Background background = region.getBackground();
        if (background == null || background.getFills().isEmpty()) return null;
        BackgroundFill fill = background.getFills().getFirst();
        return fill.getFill();
    }

    private static Color colour(Paint paint) {
        return paint instanceof Color colour ? colour : null;
    }

    private static Color textFill(TextField field) {
        Node text = field.lookup(".text");
        return text instanceof Text node && node.getFill() instanceof Color colour ? colour : null;
    }

    private static double contrast(Paint text, Color surface) {
        if (!(text instanceof Color foreground) || surface == null) {
            throw new IllegalStateException("Cannot measure contrast without two colours");
        }
        double high = Math.max(luminance(foreground), luminance(surface));
        double low = Math.min(luminance(foreground), luminance(surface));
        return (high + 0.05) / (low + 0.05);
    }

    private static double luminance(Color colour) {
        return 0.2126 * channel(colour.getRed())
                + 0.7152 * channel(colour.getGreen())
                + 0.0722 * channel(colour.getBlue());
    }

    private static double channel(double value) {
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Run the spike from inside the Nexus build.");
    }

    private static WritableImage capture(Scene scene) {
        return scene.getRoot().snapshot(null, new WritableImage(
                (int) scene.getWidth(), (int) scene.getHeight()));
    }

    private static void writePng(WritableImage image, Path target) throws Exception {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) buffered.setRGB(x, y, reader.getArgb(x, y));
        }
        ImageIO.write(buffered, "png", target.toFile());
    }

    public static void main(String[] args) {
        launch(args);
        if (!FAILURES.isEmpty()) {
            System.out.printf("[spike] %d proof-of-concept check(s) failed%n", FAILURES.size());
            for (String failure : FAILURES) System.out.printf("[spike]   - %s%n", failure);
            System.exit(1);
        }
        System.out.println("[spike] every proof-of-concept check passed");
    }
}
