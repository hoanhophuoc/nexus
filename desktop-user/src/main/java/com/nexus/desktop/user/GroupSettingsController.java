package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.ConfirmDialog;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.GroupMember;
import com.nexus.sdk.model.GroupMemberPage;
import com.nexus.sdk.model.RenameGroupRequest;
import com.nexus.sdk.model.SetGroupMemberRoleRequest;
import com.nexus.sdk.model.User;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.List;

/** FXML controller for the group administration surface. */
public final class GroupSettingsController {
    private final UserAppContext context;
    private final I18n i18n;
    private final Conversation conversation;
    private final Runnable onChanged;
    private final Runnable onLeft;
    private final Runnable onClose;

    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private TextField nameField;
    @FXML private Button renameButton;
    @FXML private ListView<GroupMember> membersList;
    @FXML private Button promoteButton;
    @FXML private Button demoteButton;
    @FXML private Button removeButton;
    @FXML private Button leaveButton;
    @FXML private Button closeButton;
    @FXML private Label statusLabel;

    private boolean currentUserAdmin;

    GroupSettingsController(UserAppContext context, Conversation conversation,
                            Runnable onChanged, Runnable onLeft, Runnable onClose) {
        this.context = context;
        this.i18n = context.i18n();
        this.conversation = conversation;
        this.onChanged = onChanged;
        this.onLeft = onLeft;
        this.onClose = onClose;
    }

    @FXML
    private void initialize() {
        titleLabel.setText(conversation.getName() == null || conversation.getName().isBlank()
                ? i18n.get("groupSettings.title") : conversation.getName());
        nameField.setText(conversation.getName());
        membersList.setCellFactory(ignored -> memberCell());
        nameField.setAccessibleText(i18n.get("groupSettings.namePrompt"));
        membersList.setAccessibleText(i18n.get("groupSettings.members"));
        membersList.setPlaceholder(UiComponents.emptyState(i18n.get("groupSettings.noMembers"),
                i18n.get("groupSettings.noMembersHint"), null, null));
        renameButton.setOnAction(event -> rename());
        promoteButton.setOnAction(event -> setRole(GroupMember.RoleEnum.ADMIN));
        demoteButton.setOnAction(event -> setRole(GroupMember.RoleEnum.MEMBER));
        removeButton.setOnAction(event -> removeMember());
        leaveButton.setOnAction(event -> leave());
        closeButton.setOnAction(event -> onClose.run());
        membersList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> updateActions());
        loadMembers();
    }

    private void loadMembers() {
        status(i18n.get("groupSettings.loading"));
        membersList.setPlaceholder(UiComponents.loadingState(i18n.get("groupSettings.loading")));
        context.runAsync(this::listMembers, members -> {
            membersList.setItems(FXCollections.observableArrayList(members));
            currentUserAdmin = members.stream()
                    .filter(member -> context.userId().equals(member.getUser().getId()))
                    .anyMatch(member -> member.getRole() == GroupMember.RoleEnum.ADMIN);
            renameButton.setDisable(!currentUserAdmin);
            status(currentUserAdmin ? i18n.get("groupSettings.adminHint")
                    : i18n.get("groupSettings.memberHint"));
            updateActions();
        }, exception -> {
            membersList.setPlaceholder(UiComponents.errorState(i18n.get("error.title"),
                    UserErrors.statusMessage(exception, i18n), i18n.get("shell.retry"), this::loadMembers));
            fail(exception);
        });
    }

    private List<GroupMember> listMembers() throws ApiException {
        List<GroupMember> members = new ArrayList<>();
        String cursor = null;
        do {
            GroupMemberPage page = context.session().api().listGroupMembers(
                    conversation.getId(), cursor, 100);
            if (page == null || page.getItems() == null) break;
            members.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return members;
    }

    private void rename() {
        String name = nameField.getText() == null ? "" : nameField.getText().strip();
        if (name.isBlank()) {
            status(i18n.get("groupSettings.nameRequired"));
            return;
        }
        if (!currentUserAdmin) {
            status(i18n.get("groupSettings.adminOnly"));
            return;
        }
        status(i18n.get("groupSettings.renaming"));
        renameButton.setDisable(true);
        context.runAsync(() -> {
            context.session().api().renameGroup(conversation.getId(),
                    new RenameGroupRequest().name(name));
            return null;
        }, ignored -> {
            renameButton.setDisable(false);
            conversation.setName(name);
            titleLabel.setText(name);
            onChanged.run();
            status(i18n.get("groupSettings.renamed"));
        }, exception -> {
            renameButton.setDisable(false);
            fail(exception);
        });
    }

    private void setRole(GroupMember.RoleEnum role) {
        GroupMember selected = membersList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("groupSettings.chooseMember"));
            return;
        }
        if (!currentUserAdmin) {
            status(i18n.get("groupSettings.adminOnly"));
            return;
        }
        if (context.userId().equals(selected.getUser().getId())) {
            status(i18n.get("groupSettings.cannotChangeSelf"));
            return;
        }
        status(i18n.get(role == GroupMember.RoleEnum.ADMIN
                ? "groupSettings.promoting" : "groupSettings.demoting"));
        context.runAsync(() -> {
            context.session().api().setGroupMemberRole(conversation.getId(),
                    selected.getUser().getId(), new SetGroupMemberRoleRequest().role(
                            SetGroupMemberRoleRequest.RoleEnum.valueOf(role.name())));
            return null;
        }, ignored -> {
            status(i18n.get(role == GroupMember.RoleEnum.ADMIN
                    ? "groupSettings.promoted" : "groupSettings.demoted"));
            loadMembers();
            onChanged.run();
        }, this::fail);
    }

    private void removeMember() {
        GroupMember selected = membersList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("groupSettings.chooseMember"));
            return;
        }
        if (context.userId().equals(selected.getUser().getId())) {
            status(i18n.get("groupSettings.useLeave"));
            return;
        }
        if (!currentUserAdmin) {
            status(i18n.get("groupSettings.adminOnly"));
            return;
        }
        if (!confirm(i18n.get("groupSettings.removeTitle"),
                i18n.format("groupSettings.removeMessage", name(selected.getUser())),
                i18n.get("groupSettings.remove"))) return;
        status(i18n.get("groupSettings.removing"));
        context.runAsync(() -> {
            context.groups().removeMember(conversation.getId(), selected.getUser().getId());
            return null;
        }, ignored -> {
            status(i18n.get("groupSettings.removed"));
            loadMembers();
            onChanged.run();
        }, this::fail);
    }

    private void leave() {
        if (!confirm(i18n.get("groupSettings.leaveTitle"),
                i18n.get("groupSettings.leaveMessage"), i18n.get("groupSettings.leave"))) return;
        status(i18n.get("groupSettings.leaving"));
        context.runAsync(() -> {
            context.groups().leaveGroup(conversation.getId());
            return null;
        }, ignored -> {
            onChanged.run();
            onLeft.run();
            onClose.run();
        }, this::fail);
    }

    private void updateActions() {
        GroupMember selected = membersList.getSelectionModel().getSelectedItem();
        boolean other = selected != null && !context.userId().equals(selected.getUser().getId());
        promoteButton.setDisable(!currentUserAdmin || !other
                || selected.getRole() == GroupMember.RoleEnum.ADMIN);
        demoteButton.setDisable(!currentUserAdmin || !other
                || selected.getRole() == GroupMember.RoleEnum.MEMBER);
        removeButton.setDisable(!currentUserAdmin || !other);
    }

    private boolean confirm(String title, String message, String action) {
        return ConfirmDialog.show(statusLabel.getScene() == null ? null
                        : statusLabel.getScene().getWindow(), context.themeManager(),
                title, message, action, i18n.get("dialog.cancel"), true);
    }

    private ListCell<GroupMember> memberCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(GroupMember member, boolean empty) {
                super.updateItem(member, empty);
                if (empty || member == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String role = member.getRole() == GroupMember.RoleEnum.ADMIN
                        ? i18n.get("dialog.roleAdmin") : i18n.get("dialog.roleMember");
                setGraphic(UiComponents.personRow(UiComponents.avatar(name(member.getUser()), 30),
                        name(member.getUser()),
                        i18n.format("groupSettings.memberMeta", "@" + member.getUser().getUsername(),
                                role),
                        true, i18n.get("common.online"), i18n.get("common.offline")));
                setText(null);
            }
        };
    }

    private void status(String text) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().remove("error");
    }

    private void fail(Exception exception) {
        statusLabel.setText(UserErrors.statusMessage(exception, i18n));
        if (!statusLabel.getStyleClass().contains("error")) statusLabel.getStyleClass().add("error");
    }

    private static String name(User user) {
        return user.getFullName() == null || user.getFullName().isBlank()
                ? user.getUsername() : user.getFullName();
    }
}
