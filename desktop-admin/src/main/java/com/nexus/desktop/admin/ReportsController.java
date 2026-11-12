package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.sdk.model.AdminDecideReportRequest;
import com.nexus.sdk.model.Report;
import com.nexus.sdk.model.ReportPage;
import com.nexus.sdk.model.ReportSelectedEvidenceInner;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Reports workspace: filter reports, inspect submitted evidence and record decisions. */
public final class ReportsController {
    private final AdminAppContext context;
    private final I18n i18n;
    private Report pendingDecisionReport;
    private boolean pendingUphold;
    private Node confirmationFocus;

    @FXML private TextField reportedUsernameField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private TextField noteField;
    @FXML private CheckBox lockOnUphold;
    @FXML private Button loadButton;
    @FXML private Label statusLabel;
    @FXML private TableView<Report> reportsTable;
    @FXML private TableColumn<Report, String> reportedColumn;
    @FXML private TableColumn<Report, String> reporterColumn;
    @FXML private TableColumn<Report, String> statusColumn;
    @FXML private TableColumn<Report, String> reasonColumn;
    @FXML private TableColumn<Report, String> createdColumn;
    @FXML private TableColumn<Report, String> evidenceColumn;
    @FXML private TableColumn<Report, String> decisionColumn;
    @FXML private Label evidenceTitle;
    @FXML private Label evidenceStatus;
    @FXML private TableView<ReportSelectedEvidenceInner> evidenceTable;
    @FXML private TableColumn<ReportSelectedEvidenceInner, String> evidenceMessageColumn;
    @FXML private TableColumn<ReportSelectedEvidenceInner, String> evidenceContentColumn;
    @FXML private VBox decisionConfirmBox;
    @FXML private Label decisionConfirmLabel;
    @FXML private Button confirmDecisionButton;
    @FXML private Button cancelDecisionButton;

    ReportsController(AdminAppContext context) {
        this.context = context;
        this.i18n = context.i18n();
    }

    @FXML
    private void initialize() {
        AdminUi.bind(reportedColumn, report -> report.getReportedUser() == null ? ""
                : report.getReportedUser().getUsername());
        AdminUi.bind(reporterColumn, report -> report.getReporter() == null ? ""
                : report.getReporter().getUsername());
        AdminUi.bind(statusColumn, this::statusText);
        AdminUi.bind(reasonColumn, Report::getReason);
        AdminUi.bind(createdColumn, report -> AdminUi.time(report.getCreatedAt(), i18n));
        AdminUi.bind(evidenceMessageColumn, evidence -> evidence.getMessageId() == null
                ? "" : evidence.getMessageId().toString());
        AdminUi.bind(evidenceContentColumn, ReportSelectedEvidenceInner::getSubmittedContent);
        AdminUi.bindAction(evidenceColumn, this::evidenceButton);
        AdminUi.bindAction(decisionColumn, this::decisionButtons);
        reportsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        evidenceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        reportsTable.setAccessibleText(i18n.get("admin.reports.title"));
        evidenceTable.setAccessibleText(i18n.get("admin.reports.evidenceTitle"));
        reportedUsernameField.setAccessibleText(i18n.get("admin.reports.filterPrompt"));
        fromDate.setAccessibleText(i18n.get("admin.reports.from"));
        toDate.setAccessibleText(i18n.get("admin.reports.to"));
        noteField.setAccessibleText(i18n.get("admin.reports.notePrompt"));
        evidenceTitle.setText(i18n.get("admin.reports.evidenceTitle"));
        evidenceStatus.setText(i18n.get("admin.reports.evidenceHint"));
        AdminUi.empty(evidenceTable, i18n, "admin.reports.evidenceEmptyTitle",
                "admin.reports.evidenceEmptyHint");
        reportsTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (selected != null) showEvidence(selected);
                });
        loadButton.setOnAction(event -> load());
        reportedUsernameField.setOnAction(event -> load());
        confirmDecisionButton.setOnAction(event -> confirmDecision());
        cancelDecisionButton.setOnAction(event -> hideDecisionConfirmation());
        load();
    }

    private Button evidenceButton(Report report) {
        Button button = new Button(i18n.get("admin.reports.evidenceView"));
        button.getStyleClass().add("secondary-button");
        button.setAccessibleText(i18n.format("admin.reports.evidenceViewAccessible",
                report.getReportedUser() == null ? "" : report.getReportedUser().getUsername()));
        button.setOnAction(event -> {
            reportsTable.getSelectionModel().select(report);
            showEvidence(report);
            evidenceTable.requestFocus();
        });
        return button;
    }

    private Node decisionButtons(Report report) {
        Button uphold = new Button(i18n.get("admin.reports.uphold"));
        uphold.getStyleClass().add("danger-button");
        uphold.setAccessibleText(i18n.format("admin.reports.upholdAccessible",
                report.getReportedUser() == null ? "" : report.getReportedUser().getUsername()));
        Button dismiss = new Button(i18n.get("admin.reports.dismiss"));
        dismiss.getStyleClass().add("secondary-button");
        dismiss.setAccessibleText(i18n.format("admin.reports.dismissAccessible",
                report.getReportedUser() == null ? "" : report.getReportedUser().getUsername()));
        uphold.setOnAction(event -> requestDecision(report, true));
        dismiss.setOnAction(event -> requestDecision(report, false));
        HBox box = new HBox(6, uphold, dismiss);
        box.getStyleClass().add("admin-action-row");
        return box;
    }

    private void showEvidence(Report report) {
        List<ReportSelectedEvidenceInner> evidence = report.getSelectedEvidence() == null
                ? List.of() : report.getSelectedEvidence();
        evidenceTable.getItems().setAll(evidence);
        AdminUi.empty(evidenceTable, i18n, "admin.reports.evidenceEmptyTitle",
                "admin.reports.evidenceEmptyHint");
        String username = report.getReportedUser() == null ? "" : report.getReportedUser().getUsername();
        evidenceTitle.setText(i18n.format("admin.reports.evidenceFor", username));
        evidenceStatus.setText(i18n.format("admin.reports.evidenceCount", evidence.size()));
    }

    private void requestDecision(Report report, boolean uphold) {
        if (!decisionConfirmBox.isVisible() && decisionConfirmBox.getScene() != null) {
            confirmationFocus = decisionConfirmBox.getScene().getFocusOwner();
        }
        pendingDecisionReport = report;
        pendingUphold = uphold;
        String username = report.getReportedUser() == null ? "" : report.getReportedUser().getUsername();
        decisionConfirmLabel.setText(i18n.format(uphold
                ? "admin.reports.upholdConfirm" : "admin.reports.dismissConfirm", username));
        confirmDecisionButton.setText(i18n.get(uphold ? "admin.reports.uphold" : "admin.reports.dismiss"));
        confirmDecisionButton.getStyleClass().removeAll("danger-button", "secondary-button");
        confirmDecisionButton.getStyleClass().add(uphold ? "danger-button" : "secondary-button");
        decisionConfirmBox.setVisible(true);
        decisionConfirmBox.setManaged(true);
        confirmDecisionButton.requestFocus();
    }

    private void hideDecisionConfirmation() {
        boolean wasVisible = decisionConfirmBox.isVisible();
        decisionConfirmBox.setVisible(false);
        decisionConfirmBox.setManaged(false);
        pendingDecisionReport = null;
        confirmDecisionButton.setText(i18n.get("admin.reports.confirmDecisionAction"));
        confirmDecisionButton.setDisable(false);
        if (wasVisible && confirmationFocus != null) confirmationFocus.requestFocus();
        confirmationFocus = null;
    }

    private void confirmDecision() {
        Report report = pendingDecisionReport;
        boolean uphold = pendingUphold;
        hideDecisionConfirmation();
        if (report != null) decide(report, uphold);
    }

    private void decide(Report report, boolean uphold) {
        statusLabel.setText(i18n.get(uphold ? "admin.reports.upholding" : "admin.reports.dismissing"));
        context.runAsync(() -> {
            String note = noteField.getText();
            context.session().api().adminDecideReport(report.getId(), new AdminDecideReportRequest()
                    .decision(uphold ? AdminDecideReportRequest.DecisionEnum.UPHOLD
                            : AdminDecideReportRequest.DecisionEnum.DISMISS)
                    .lockReportedUser(uphold && lockOnUphold.isSelected())
                    .note(note == null || note.isBlank() ? null : note.strip()));
            return null;
        }, ignored -> {
            statusLabel.setText(i18n.get(uphold ? "admin.reports.upheld" : "admin.reports.dismissed"));
            load();
        }, exception -> statusLabel.setText(AdminErrors.message(exception, i18n)));
    }

    private void load() {
        hideDecisionConfirmation();
        String username = reportedUsernameField.getText() == null
                ? null : reportedUsernameField.getText().strip();
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        OffsetDateTime fromTime = AdminUi.startOfDay(from);
        OffsetDateTime toTime = to == null ? null : AdminUi.startOfDay(to.plusDays(1));
        statusLabel.setText(i18n.get("admin.reports.loading"));
        AdminUi.loading(reportsTable, i18n);
        evidenceTable.getItems().clear();
        AdminUi.empty(evidenceTable, i18n, "admin.reports.evidenceEmptyTitle",
                "admin.reports.evidenceEmptyHint");
        evidenceStatus.setText(i18n.get("admin.reports.evidenceHint"));
        context.runAsync(() -> {
            List<Report> all = new ArrayList<>();
            String cursor = null;
            do {
                ReportPage page = context.session().api().adminListReports(
                        username == null || username.isBlank() ? null : username,
                        fromTime, toTime, "createdAt", "desc", cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(reportsTable, all);
            AdminUi.empty(reportsTable, i18n,
                    "admin.reports.emptyTitle", "admin.reports.emptyHint");
            statusLabel.setText(i18n.format("admin.reports.count", all.size()));
            if (!all.isEmpty()) {
                reportsTable.getSelectionModel().selectFirst();
                showEvidence(all.getFirst());
            }
        }, exception -> {
            statusLabel.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(reportsTable, i18n, exception, this::load);
        });
    }

    private String statusText(Report report) {
        if (report.getStatus() == null) return "";
        return switch (report.getStatus()) {
            case PENDING -> i18n.get("admin.reports.statusPending");
            case UPHELD -> i18n.get("admin.reports.statusUpheld");
            case DISMISSED -> i18n.get("admin.reports.statusDismissed");
        };
    }
}
