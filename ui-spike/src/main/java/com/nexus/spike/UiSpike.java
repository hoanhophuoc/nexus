package com.nexus.spike;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
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
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * AtlantaFX 3.0.0 + Ikonli 12.4.0 proof of concept (docs/ui-ux-tooling.md).
 *
 * <p>Renders the same control sheet twice: once with the stock Primer theme as the JavaFX user-agent
 * stylesheet, once with the real Nexus {@code theme.css} added as an application stylesheet on top.
 * The screenshots and the printed colours answer the two questions the decision record leaves open:
 * whether the pair works on JavaFX 27.0.1, and whether Nexus tokens still win the cascade.</p>
 *
 * <p>Run it with {@code xvfb-run -a ./gradlew :ui-spike:run}; output lands in
 * {@code ui-spike/build/ui-spike/}.</p>
 */
public final class UiSpike extends Application {
    private static final Path SHARED_UI = Path.of("..", "desktop-shared", "src", "main", "resources",
            "com", "nexus", "desktop", "shared", "ui");

    @Override
    public void start(Stage stage) throws Exception {
        Path output = Path.of("build", "ui-spike");
        Files.createDirectories(output);
        loadNexusFont();
        for (Theme theme : List.of(new PrimerLight(), new PrimerDark())) {
            String name = theme.getName().startsWith("Primer Dark") ? "dark" : "light";
            render(stage, theme, false, output.resolve("primer-" + name + ".png"));
            render(stage, theme, true, output.resolve("nexus-on-primer-" + name + ".png"));
        }
        Platform.exit();
    }

    private static void render(Stage stage, Theme atlanta, boolean withNexus, Path target)
            throws Exception {
        Application.setUserAgentStylesheet(atlanta.getUserAgentStylesheet());
        Parent root = sheet();
        Scene scene = new Scene(root, 720, 760);
        if (withNexus) {
            // Application stylesheet last: Nexus tokens must override the stock theme, never the
            // other way round. The order is asserted by the colour printout below.
            scene.getStylesheets().add(SHARED_UI.resolve("theme.css").toUri().toString());
            root.getStyleClass().add(atlanta.getName().startsWith("Primer Dark") ? "theme-dark"
                    : "theme-light");
        }
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        writePng(scene.getRoot().snapshot(null, new WritableImage(
                (int) scene.getWidth(), (int) scene.getHeight())), target);
        report(atlanta, withNexus, scene.getStylesheets(), root, target);
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
        field.setPromptText("Write a message");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        ComboBox<String> combo = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList("Direct chat", "Group chat"));
        combo.getSelectionModel().selectFirst();
        DatePicker date = new DatePicker(LocalDate.of(2026, 11, 15));
        CheckBox check = new CheckBox("Lock the account on uphold");
        check.setSelected(true);
        MenuButton menu = new MenuButton("Actions", null, new MenuItem("For me"), new MenuItem("For everyone"));
        ListView<String> list = new ListView<>(javafx.collections.FXCollections.observableArrayList(
                "Alice", "Bob", "Charlie"));
        list.getSelectionModel().select(1);
        list.setPrefHeight(110);
        TableView<String> table = new TableView<>();
        TableColumn<String, String> column = new TableColumn<>("Account");
        column.setCellValueFactory(data -> new javafx.beans.property.ReadOnlyStringWrapper(data.getValue()));
        table.getColumns().add(column);
        table.getItems().addAll("alice", "bob");
        table.setPrefHeight(110);

        Label status = new Label("Connected");
        status.getStyleClass().add("caption");
        Label title = new Label("Nexus control sheet");
        title.getStyleClass().add("title");

        FlowPane icons = new FlowPane(10, 10,
                new FontIcon(Feather.MOON), new FontIcon(Feather.SUN),
                new FontIcon(Feather.MESSAGE_SQUARE), new FontIcon(Feather.USERS),
                new FontIcon(Feather.SHIELD), new FontIcon(Feather.SEND));

        VBox body = new VBox(12, title, status, new FlowPane(8, 8, primary, secondary, ghost, danger,
                disabled, icon, new ProgressIndicator()), field, password, combo, date, check, menu,
                icons, list, table);
        body.setPadding(new Insets(20));
        return body;
    }

    private static void report(Theme atlanta, boolean withNexus, List<String> stylesheets,
                               Parent root, Path target) {
        Region field = (Region) root.lookup(".text-field");
        Label title = (Label) root.lookup(".title");
        System.out.printf("[spike] %s nexus=%s -> %s%n", atlanta.getName(), withNexus,
                target.getFileName());
        System.out.printf("[spike]   stylesheets=%s%n", stylesheets.stream()
                .map(url -> url.substring(url.lastIndexOf('/') + 1)).toList());
        System.out.printf("[spike]   text-field background=%s%n", background(field));
        System.out.printf("[spike]   title font=%s %.1f fill=%s%n", title.getFont().getFamily(),
                title.getFont().getSize(), title.getTextFill());
        FontIcon moon = (FontIcon) root.lookupAll(".ikonli-font-icon").stream().findFirst().orElse(null);
        System.out.printf("[spike]   icon=%s%n", moon == null ? "missing" : moon.getIconCode());
    }

    private static Object background(Region region) {
        if (region == null) return "missing";
        Background background = region.getBackground();
        if (background == null || background.getFills().isEmpty()) return "none";
        BackgroundFill fill = background.getFills().getFirst();
        return fill.getFill();
    }

    private static void loadNexusFont() {
        for (String file : List.of("fonts/nexusSans-Regular.otf", "fonts/nexusSans-Bold.otf")) {
            Path font = SHARED_UI.resolve(file);
            javafx.scene.text.Font.loadFont(font.toUri().toString(), 13);
        }
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
    }
}
