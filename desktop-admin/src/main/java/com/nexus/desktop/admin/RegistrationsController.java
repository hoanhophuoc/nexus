package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.sdk.model.AdminUser;
import com.nexus.sdk.model.AdminUserPage;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Registrations screen: date-bounded registration list with search. */
public final class RegistrationsController {
    private final AdminAppContext context;
    private final I18n i18n;

    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private TextField filterField;
    @FXML private Button applyButton;
    @FXML private Label statusLabel;
    @FXML private TableView<AdminUser> registrationsTable;
    @FXML private TableColumn<AdminUser, String> usernameColumn;
    @FXML private TableColumn<AdminUser, String> fullNameColumn;
    @FXML private TableColumn<AdminUser, String> emailColumn;
    @FXML private TableColumn<AdminUser, String> statusColumn;
    @FXML private TableColumn<AdminUser, String> createdColumn;

    RegistrationsController(AdminAppContext context) {
        this.context = context;
        this.i18n = context.i18n();
    }

    @FXML
    private void initialize() {
        AdminUi.bind(usernameColumn, AdminUser::getUsername);
        AdminUi.bind(fullNameColumn, AdminUser::getFullName);
        AdminUi.bind(emailColumn, AdminUser::getEmail);
        AdminUi.bind(statusColumn, user -> i18n.get(user.getStatus() == AdminUser.StatusEnum.LOCKED
                ? "admin.accounts.statusLocked" : "admin.accounts.statusActive"));
        AdminUi.bind(createdColumn, user -> AdminUi.time(user.getCreatedAt(), i18n));
        registrationsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        fromDate.setAccessibleText(i18n.get("admin.registrations.from"));
        toDate.setAccessibleText(i18n.get("admin.registrations.to"));
        filterField.setAccessibleText(i18n.get("admin.registrations.filterPrompt"));
        registrationsTable.setAccessibleText(i18n.get("admin.registrations.title"));
        fromDate.setValue(LocalDate.now().minusDays(30));
        toDate.setValue(LocalDate.now());
        applyButton.setOnAction(event -> load());
        filterField.setOnAction(event -> load());
        load();
    }

    private void load() {
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        if (from == null || to == null) {
            statusLabel.setText(i18n.get("admin.registrations.dateRequired"));
            return;
        }
        if (!from.isBefore(to)) {
            statusLabel.setText(i18n.get("admin.registrations.dateOrder"));
            return;
        }
        String filter = filterField.getText() == null ? null : filterField.getText().strip();
        OffsetDateTime fromTime = AdminUi.startOfDay(from);
        OffsetDateTime toTime = AdminUi.startOfDay(to.plusDays(1));
        statusLabel.setText(i18n.get("admin.registrations.loading"));
        AdminUi.loading(registrationsTable, i18n);
        context.runAsync(() -> {
            List<AdminUser> all = new ArrayList<>();
            String cursor = null;
            do {
                AdminUserPage page = context.session().api().adminListRegistrations(
                        fromTime, toTime,
                        filter == null || filter.isBlank() ? null : filter,
                        "createdAt", "desc", cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(registrationsTable, all);
            AdminUi.empty(registrationsTable, i18n,
                    "admin.registrations.emptyTitle", "admin.registrations.emptyHint");
            statusLabel.setText(i18n.format("admin.registrations.count", all.size()));
        }, exception -> {
            statusLabel.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(registrationsTable, i18n, exception, this::load);
        });
    }
}
