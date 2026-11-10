package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.UiComponents;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;

/** FXML shell: navigation between the six admin workspaces loaded from their own FXML files. */
public final class AdminShellController {
    private final AdminAppContext context;
    private final Runnable onSignedOut;
    private final I18n i18n;

    @FXML private Label identityLabel;
    @FXML private StackPane avatarHost;
    @FXML private ListView<AdminScreen> navigationList;
    @FXML private StackPane contentHost;
    @FXML private Button themeButton;
    @FXML private Button signOutButton;

    AdminShellController(AdminAppContext context, Runnable onSignedOut) {
        this.context = context;
        this.i18n = context.i18n();
        this.onSignedOut = onSignedOut;
    }

    @FXML
    private void initialize() {
        String name = context.administratorName();
        identityLabel.setText(i18n.format("admin.shell.identity", name));
        identityLabel.setAccessibleText(identityLabel.getText());
        avatarHost.getChildren().setAll(UiComponents.avatar(name, 38));
        avatarHost.setAccessibleText(name);
        avatarHost.setFocusTraversable(false);

        navigationList.setAccessibleText(i18n.get("admin.nav.label"));
        navigationList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(AdminScreen screen, boolean empty) {
                super.updateItem(screen, empty);
                setText(empty || screen == null ? null : screen.label(i18n));
                setAccessibleText(getText());
            }
        });
        navigationList.setItems(FXCollections.observableArrayList(AdminScreen.values()));
        navigationList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (selected != null) showScreen(selected);
                });
        navigationList.getSelectionModel().select(AdminScreen.ACCOUNTS);

        themeButton.setOnAction(event -> {
            context.themeManager().toggle();
            Scene scene = themeButton.getScene();
            if (scene != null) context.themeManager().apply(scene);
            updateThemeButton();
        });
        signOutButton.setOnAction(event -> context.signOut(onSignedOut));
        updateThemeButton();
    }

    private void showScreen(AdminScreen screen) {
        try {
            Object controller = switch (screen) {
                case ACCOUNTS -> new AccountsController(context);
                case REPORTS -> new ReportsController(context);
                case GROUPS -> new GroupsController(context);
                case REGISTRATIONS -> new RegistrationsController(context);
                case ANALYTICS -> new AnalyticsController(context);
                case AUDIT -> new AuditController(context);
            };
            Parent view = AdminViewLoader.load(screen.resource(), controller, i18n);
            contentHost.getChildren().setAll(view);
        } catch (RuntimeException exception) {
            contentHost.getChildren().setAll(UiComponents.errorState(
                    i18n.get("admin.error.title"),
                    AdminErrors.message(exception, i18n),
                    i18n.get("admin.common.retry"),
                    () -> showScreen(screen)));
        }
    }

    private void updateThemeButton() {
        boolean dark = context.themeManager().isDark();
        themeButton.setText(i18n.get(dark ? "admin.theme.iconLight" : "admin.theme.iconDark"));
        themeButton.setAccessibleText(i18n.get(dark ? "admin.theme.toLight" : "admin.theme.toDark"));
    }
}
