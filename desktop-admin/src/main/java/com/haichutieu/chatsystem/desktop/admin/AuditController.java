package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.model.AuditEvent;
import com.haichutieu.chatsystem.sdk.model.AuditEventPage;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.ArrayList;
import java.util.List;

/** Audit screen: read-only, cursor-paginated administrative event log. */
public final class AuditController {
    private final AdminAppContext context;
    private final I18n i18n;

    @FXML private Label statusLabel;
    @FXML private Button reloadButton;
    @FXML private TableView<AuditEvent> auditTable;
    @FXML private TableColumn<AuditEvent, String> whenColumn;
    @FXML private TableColumn<AuditEvent, String> actionColumn;
    @FXML private TableColumn<AuditEvent, String> actorColumn;
    @FXML private TableColumn<AuditEvent, String> targetColumn;

    AuditController(AdminAppContext context) {
        this.context = context;
        this.i18n = context.i18n();
    }

    @FXML
    private void initialize() {
        AdminUi.bind(whenColumn, event -> AdminUi.time(event.getOccurredAt(), i18n));
        AdminUi.bind(actionColumn, AuditEvent::getAction);
        AdminUi.bind(actorColumn, event -> event.getActorId() == null
                ? i18n.get("admin.audit.deletedActor") : event.getActorId().toString());
        AdminUi.bind(targetColumn, event -> event.getTargetId() == null
                ? "" : event.getTargetId().toString());
        auditTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        auditTable.setAccessibleText(i18n.get("admin.audit.title"));
        reloadButton.setOnAction(event -> load());
        load();
    }

    private void load() {
        statusLabel.setText(i18n.get("admin.audit.loading"));
        AdminUi.loading(auditTable, i18n);
        context.runAsync(() -> {
            List<AuditEvent> all = new ArrayList<>();
            String cursor = null;
            do {
                AuditEventPage page = context.session().api().adminListAuditEvents(cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(auditTable, all);
            AdminUi.empty(auditTable, i18n, "admin.audit.emptyTitle", "admin.audit.emptyHint");
            statusLabel.setText(i18n.format("admin.audit.count", all.size()));
        }, exception -> {
            statusLabel.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(auditTable, i18n, exception, this::load);
        });
    }
}
