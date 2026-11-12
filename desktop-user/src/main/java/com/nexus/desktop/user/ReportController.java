package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.model.ReportSubmission;
import com.nexus.sdk.model.ReportSubmissionSelectedEvidenceInner;
import com.nexus.sdk.model.User;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.UUID;

/** FXML controller for reporting a user with explicitly selected message evidence. */
public final class ReportController {
    private final UserAppContext context;
    private final I18n i18n;
    private final UUID targetId;
    private final String targetName;
    private final List<CachedMessage> evidence;
    private final Runnable onClose;
    private final DateTimeFormatter timeFormat;

    @FXML private Label titleLabel;
    @FXML private Label targetLabel;
    @FXML private Label reasonLabel;
    @FXML private TextArea reasonField;
    @FXML private Label evidenceLabel;
    @FXML private ListView<CachedMessage> evidenceList;
    @FXML private CheckBox consentCheck;
    @FXML private Label statusLabel;
    @FXML private Button submitButton;
    @FXML private Button cancelButton;

    ReportController(UserAppContext context, User target, List<CachedMessage> evidence,
                     Runnable onClose) {
        this.context = context;
        this.i18n = context.i18n();
        this.targetId = target.getId();
        this.targetName = target.getFullName() == null || target.getFullName().isBlank()
                ? target.getUsername() : target.getFullName();
        this.evidence = List.copyOf(evidence);
        this.onClose = onClose;
        this.timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(i18n.locale()).withZone(ZoneId.systemDefault());
    }

    @FXML
    private void initialize() {
        reasonLabel.setLabelFor(reasonField);
        evidenceLabel.setLabelFor(evidenceList);
        reasonField.setAccessibleText(i18n.get("report.reason"));
        evidenceList.setAccessibleText(i18n.get("report.evidence"));
        targetLabel.setText(i18n.format("report.target", targetName));
        evidenceList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        evidenceList.setItems(FXCollections.observableArrayList(evidence));
        evidenceList.setPlaceholder(UiComponents.emptyState(i18n.get("report.noEvidence"),
                i18n.get("report.noEvidenceHint"), null, null));
        evidenceList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(CachedMessage message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label content = new Label(message.content());
                content.getStyleClass().add("conversation-name");
                content.setWrapText(true);
                Label meta = new Label(timeFormat.format(message.createdAt()));
                meta.getStyleClass().add("conversation-meta");
                setGraphic(new javafx.scene.layout.VBox(2, content, meta));
                setText(null);
            }
        });
        submitButton.setOnAction(event -> submit());
        cancelButton.setOnAction(event -> onClose.run());
        if (evidence.isEmpty()) consentCheck.setDisable(true);
    }

    private void submit() {
        String reason = reasonField.getText() == null ? "" : reasonField.getText().strip();
        if (reason.isBlank()) {
            showError(i18n.get("report.reasonRequired"));
            return;
        }
        List<CachedMessage> selected = List.copyOf(evidenceList.getSelectionModel().getSelectedItems());
        if (!selected.isEmpty() && !consentCheck.isSelected()) {
            showError(i18n.get("report.consentRequired"));
            return;
        }
        ReportSubmission submission = new ReportSubmission().reportedUserId(targetId).reason(reason)
                .evidenceConsent(!selected.isEmpty() && consentCheck.isSelected());
        for (CachedMessage message : selected) {
            submission.addSelectedEvidenceItem(new ReportSubmissionSelectedEvidenceInner()
                    .messageId(message.id()).submittedContent(message.content()));
        }
        submitButton.setDisable(true);
        statusLabel.getStyleClass().remove("error");
        statusLabel.setText(i18n.get("report.sending"));
        context.runAsync(() -> {
            context.session().api().reportUser(submission);
            return null;
        }, ignored -> {
            submitButton.setDisable(false);
            statusLabel.setText(i18n.get("report.submitted"));
            onClose.run();
        }, exception -> {
            submitButton.setDisable(false);
            showError(UserErrors.statusMessage(exception, i18n));
        });
    }

    private void showError(String message) {
        statusLabel.setText(message);
        if (!statusLabel.getStyleClass().contains("error")) statusLabel.getStyleClass().add("error");
    }
}
