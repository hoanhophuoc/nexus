package com.nexus.desktop.admin;

import com.nexus.desktop.shared.ui.I18n;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.ConversationPage;
import com.nexus.sdk.model.GroupMember;
import com.nexus.sdk.model.GroupMemberPage;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.List;

/** Groups screen: list groups and inspect the selected group's member roster. */
public final class GroupsController {
    private final AdminAppContext context;
    private final I18n i18n;

    @FXML private TextField filterField;
    @FXML private Button applyButton;
    @FXML private Label statusLabel;
    @FXML private SplitPane splitPane;
    @FXML private TableView<Conversation> groupsTable;
    @FXML private TableColumn<Conversation, String> nameColumn;
    @FXML private TableColumn<Conversation, String> createdColumn;
    @FXML private Label memberStatusLabel;
    @FXML private TableView<GroupMember> membersTable;
    @FXML private TableColumn<GroupMember, String> memberUsernameColumn;
    @FXML private TableColumn<GroupMember, String> memberFullNameColumn;
    @FXML private TableColumn<GroupMember, String> memberRoleColumn;
    @FXML private TableColumn<GroupMember, String> memberJoinedColumn;

    GroupsController(AdminAppContext context) {
        this.context = context;
        this.i18n = context.i18n();
    }

    @FXML
    private void initialize() {
        AdminUi.bind(nameColumn, Conversation::getName);
        AdminUi.bind(createdColumn, group -> AdminUi.time(group.getCreatedAt(), i18n));
        AdminUi.bind(memberUsernameColumn, member -> member.getUser() == null ? ""
                : member.getUser().getUsername());
        AdminUi.bind(memberFullNameColumn, member -> member.getUser() == null ? ""
                : member.getUser().getFullName());
        AdminUi.bind(memberRoleColumn, member -> AdminUi.role(member, i18n));
        AdminUi.bind(memberJoinedColumn, member -> i18n.format("admin.groups.joinedEpoch", member.getJoinedEpoch()));
        groupsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        membersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        filterField.setAccessibleText(i18n.get("admin.groups.filterPrompt"));
        groupsTable.setAccessibleText(i18n.get("admin.groups.title"));
        membersTable.setAccessibleText(i18n.get("admin.groups.membersTitle"));
        splitPane.setDividerPositions(0.48);
        groupsTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (selected != null) loadMembers(selected);
                });
        applyButton.setOnAction(event -> load());
        filterField.setOnAction(event -> load());
        AdminUi.empty(membersTable, i18n, "admin.groups.memberEmptyTitle", "admin.groups.memberEmptyHint");
        memberStatusLabel.setText(i18n.get("admin.groups.selectHint"));
        load();
    }

    private void load() {
        String filter = filterField.getText() == null ? null : filterField.getText().strip();
        statusLabel.setText(i18n.get("admin.groups.loading"));
        AdminUi.loading(groupsTable, i18n);
        context.runAsync(() -> {
            List<Conversation> all = new ArrayList<>();
            String cursor = null;
            do {
                ConversationPage page = context.session().api().adminListGroups(
                        filter == null || filter.isBlank() ? null : filter,
                        "createdAt", "desc", cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(groupsTable, all);
            AdminUi.empty(groupsTable, i18n, "admin.groups.emptyTitle", "admin.groups.emptyHint");
            statusLabel.setText(i18n.format("admin.groups.count", all.size()));
            if (!all.isEmpty()) {
                groupsTable.getSelectionModel().selectFirst();
            } else {
                membersTable.getItems().clear();
                memberStatusLabel.setText(i18n.get("admin.groups.selectHint"));
            }
        }, exception -> {
            statusLabel.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(groupsTable, i18n, exception, this::load);
        });
    }

    private void loadMembers(Conversation group) {
        memberStatusLabel.setText(i18n.get("admin.groups.memberLoading"));
        AdminUi.loading(membersTable, i18n);
        context.runAsync(() -> {
            GroupMemberPage page = context.session().api().adminListGroupMembers(group.getId());
            return page == null || page.getItems() == null ? List.<GroupMember>of() : page.getItems();
        }, all -> {
            AdminUi.setItems(membersTable, all);
            AdminUi.empty(membersTable, i18n,
                    "admin.groups.memberEmptyTitle", "admin.groups.memberEmptyHint");
            memberStatusLabel.setText(i18n.format("admin.groups.memberCount", all.size()));
        }, exception -> {
            memberStatusLabel.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(membersTable, i18n, exception, () -> loadMembers(group));
        });
    }
}
