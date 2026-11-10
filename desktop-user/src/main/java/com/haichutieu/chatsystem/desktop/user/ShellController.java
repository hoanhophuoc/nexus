package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.cache.CachedMessage;
import com.haichutieu.chatsystem.desktop.shared.cache.MessageSearchResult;
import com.haichutieu.chatsystem.desktop.shared.cache.PendingMessage;
import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.desktop.shared.ui.ConfirmDialog;
import com.haichutieu.chatsystem.desktop.shared.ui.UiComponents;
import com.haichutieu.chatsystem.sdk.ApiException;
import com.haichutieu.chatsystem.sdk.model.Conversation;
import com.haichutieu.chatsystem.sdk.model.CreateDirectConversationRequest;
import com.haichutieu.chatsystem.sdk.model.GroupMember;
import com.haichutieu.chatsystem.sdk.model.MyProfile;
import com.haichutieu.chatsystem.sdk.model.User;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** FXML controller for the signed-in user shell. */
public final class ShellController {
    private final UserAppContext context;
    private final I18n i18n;
    private final Runnable onSignedOut;
    private final DateTimeFormatter messageTime;
    private final DateTimeFormatter dayFormat;
    private final Map<UUID, Node> messageNodes = new HashMap<>();
    private Conversation activeConversation;

    @FXML private StackPane profileAvatarHost;
    @FXML private Label profileNameLabel;
    @FXML private Label profileStatusLabel;
    @FXML private Button themeButton;
    @FXML private Button signOutButton;
    @FXML private TextField searchField;
    @FXML private ListView<Conversation> conversationList;
    @FXML private ListView<MessageSearchResult> searchResults;
    @FXML private Label chatTitleLabel;
    @FXML private Label chatSubtitleLabel;
    @FXML private Button friendsButton;
    @FXML private Button accountButton;
    @FXML private Button newGroupButton;
    @FXML private Button addMemberButton;
    @FXML private Button removeMemberButton;
    @FXML private Button reportButton;
    @FXML private Button groupSettingsButton;
    @FXML private Button clearHistoryButton;
    @FXML private Button recoveryButton;
    @FXML private ScrollPane messageScroll;
    @FXML private VBox messageRows;
    @FXML private TextField composeField;
    @FXML private Button sendButton;
    @FXML private Circle statusDot;
    @FXML private Label statusLabel;

    ShellController(UserAppContext context, Runnable onSignedOut) {
        this.context = context;
        this.i18n = context.i18n();
        this.onSignedOut = onSignedOut;
        this.messageTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(i18n.locale()).withZone(ZoneId.systemDefault());
        this.dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", i18n.locale())
                .withZone(ZoneId.systemDefault());
    }

    @FXML
    private void initialize() {
        String name = displayName();
        profileNameLabel.setText(name);
        profileStatusLabel.setText(i18n.get("shell.profileOnline"));
        profileAvatarHost.getChildren().setAll(UiComponents.avatar(name, 38));

        searchField.setAccessibleText(i18n.get("shell.searchPrompt"));
        composeField.setAccessibleText(i18n.get("shell.composePrompt"));
        conversationList.setAccessibleText(i18n.get("shell.conversations"));
        searchResults.setAccessibleText(i18n.get("shell.searchResults"));
        profileAvatarHost.setAccessibleText(name);
        profileAvatarHost.setFocusTraversable(false);
        statusDot.setFocusTraversable(false);
        statusDot.setMouseTransparent(true);

        conversationList.setPlaceholder(UiComponents.emptyState(
                i18n.get("shell.noConversations"), i18n.get("shell.noConversationsHint"), null, null));
        conversationList.setCellFactory(ignored -> new ConversationCell());
        conversationList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (selected != null) openConversation(selected, null);
                });

        searchField.textProperty().addListener((observable, previous, query) -> refreshSearch(query));
        searchResults.setCellFactory(ignored -> new SearchResultCell());
        searchResults.setPlaceholder(UiComponents.emptyState(
                i18n.get("shell.noSearchResults"), i18n.get("shell.noSearchResultsHint"), null, null));
        searchResults.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> revealSearchResult(selected));

        composeField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                sendMessage();
                event.consume();
            }
        });
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && !searchField.getText().isBlank()) {
                searchField.clear();
                event.consume();
            }
        });

        sendButton.setOnAction(event -> sendMessage());
        friendsButton.setOnAction(event -> showFriendsDialog());
        accountButton.setOnAction(event -> showAccount());
        newGroupButton.setOnAction(event -> showCreateGroupDialog());
        addMemberButton.setOnAction(event -> showAddMemberDialog());
        removeMemberButton.setOnAction(event -> showRemoveMemberDialog());
        reportButton.setOnAction(event -> reportActiveConversation());
        groupSettingsButton.setOnAction(event -> showGroupSettings());
        clearHistoryButton.setOnAction(event -> clearHistory());
        recoveryButton.setOnAction(event -> showRecoveryCode());
        signOutButton.setOnAction(event -> signOut());
        themeButton.setOnAction(event -> toggleTheme());

        statusDot.getStyleClass().setAll("presence-dot", "offline");
        updateThemeButton();
        setConversationActionsDisabled(true);
        showEmptyChat();
        status(i18n.get("shell.connecting"), false);
        startRealtime();
        loadConversations();
    }

    private String displayName() {
        MyProfile profile = context.profile();
        if (profile == null) return "";
        if (profile.getFullName() != null && !profile.getFullName().isBlank()) {
            return profile.getFullName();
        }
        return profile.getUsername();
    }

    private void loadConversations() {
        status(i18n.get("shell.connecting"), false);
        context.runAsync(() -> {
            context.messaging().syncAllDirectConversations();
            List<Conversation> all = listConversations();
            try {
                context.groups().publishKeyPackage();
            } catch (Exception ignored) {
                // A directory outage must not hide the conversation list.
            }
            for (Conversation conversation : all) {
                if (conversation.getType() == Conversation.TypeEnum.GROUP) {
                    try {
                        context.groups().restoreRecovery(conversation.getId());
                    } catch (Exception ignored) {
                        // The commit log can still establish or advance state.
                    }
                }
            }
            syncGroups(all);
            return all;
        }, all -> {
            conversationList.setItems(FXCollections.observableArrayList(all));
            status(i18n.get("shell.connected"), true);
            if (all.isEmpty()) {
                showEmptyChat();
            } else {
                conversationList.getSelectionModel().selectFirst();
            }
            refreshSearch(searchField.getText());
        }, this::showStatusError);
    }

    private void refreshConversations() {
        if (context.session() == null) return;
        UUID selectedId = activeConversation == null ? null : activeConversation.getId();
        context.runAsync(this::listConversations, all -> {
            conversationList.setItems(FXCollections.observableArrayList(all));
            if (selectedId != null) {
                all.stream().filter(conversation -> conversation.getId().equals(selectedId))
                        .findFirst().ifPresent(conversation ->
                                conversationList.getSelectionModel().select(conversation));
            }
        }, this::showStatusError);
    }

    private List<Conversation> listConversations() throws ApiException {
        List<Conversation> all = new ArrayList<>();
        String cursor = null;
        do {
            var page = context.session().api().listConversations(cursor, 100);
            if (page == null || page.getItems() == null) {
                throw new ApiException("Conversation list is unavailable.");
            }
            all.addAll(page.getItems());
            cursor = page.getNextCursor();
        } while (cursor != null);
        return all;
    }

    private void openConversation(Conversation conversation, UUID messageToReveal) {
        activeConversation = conversation;
        boolean group = conversation.getType() == Conversation.TypeEnum.GROUP;
        reportButton.setDisable(group);
        groupSettingsButton.setDisable(!group);
        clearHistoryButton.setDisable(group);
        String title = conversation.getName() == null || conversation.getName().isBlank()
                ? i18n.get("shell.directChat") : conversation.getName();
        chatTitleLabel.setText(title);
        chatSubtitleLabel.setText(conversation.getType() == Conversation.TypeEnum.GROUP
                ? i18n.get("shell.groupSubtitle") : i18n.get("shell.directSubtitle"));
        messageRows.getChildren().setAll(UiComponents.loadingState(i18n.get("shell.loadingMessages")));
        context.runAsync(() -> {
            if (conversation.getType() == Conversation.TypeEnum.GROUP) {
                context.groups().syncGroup(conversation.getId());
            } else {
                context.messaging().syncConversation(conversation.getId());
            }
            return null;
        }, ignored -> refreshVisibleMessages(messageToReveal), exception -> {
            showStatusError(exception);
            messageRows.getChildren().setAll(UiComponents.errorState(
                    i18n.get("error.title"),
                    UserErrors.statusMessage(exception, i18n),
                    i18n.get("shell.retry"),
                    () -> openConversation(conversation, messageToReveal)));
        });
    }

    private void refreshVisibleMessages(UUID messageToReveal) {
        Conversation current = activeConversation;
        if (current == null || context.cache() == null) return;
        List<CachedMessage> stored = context.cache().messagesInConversation(current.getId());
        List<PendingMessage> pending = context.cache().pendingInConversation(current.getId());
        messageNodes.clear();
        messageRows.getChildren().clear();
        if (stored.isEmpty() && pending.isEmpty()) {
            messageRows.getChildren().setAll(UiComponents.emptyState(
                    i18n.get("shell.noMessages"), i18n.get("shell.noMessagesHint"), null, null));
        } else {
            LocalDate lastDay = null;
            for (CachedMessage message : stored) {
                LocalDate day = LocalDate.ofInstant(message.createdAt(), ZoneId.systemDefault());
                if (!day.equals(lastDay)) {
                    messageRows.getChildren().add(daySeparator(day));
                    lastDay = day;
                }
                boolean mine = context.userId() != null
                        && context.userId().toString().equals(message.senderName());
                boolean deleted = message.deletedForEveryoneAt() != null;
                Node row = messageRow(message, mine, deleted);
                messageNodes.put(message.id(), row);
                messageRows.getChildren().add(row);
            }
            for (PendingMessage pendingMessage : pending) {
                messageRows.getChildren().add(pendingRow(pendingMessage));
            }
        }
        messageScroll.setVvalue(1.0);
        if (messageToReveal != null) {
            Node target = messageNodes.get(messageToReveal);
            if (target != null) {
                int index = messageRows.getChildren().indexOf(target);
                messageScroll.setVvalue((double) index / Math.max(1, messageRows.getChildren().size()));
                target.getStyleClass().add("message-highlight");
            }
        }
        refreshSearch(searchField.getText());
    }

    private Node messageRow(CachedMessage message, boolean mine, boolean deleted) {
        String sender = mine ? i18n.get("shell.messageYou") : message.senderName();
        Label meta = new Label(sender + " · " + messageTime.format(message.createdAt()));
        meta.getStyleClass().add("message-meta");
        Label content = new Label(deleted ? i18n.get("shell.messageDeleted") : message.content());
        content.setWrapText(true);
        content.setMaxWidth(560);
        HBox metaRow = new HBox(6, meta, UiComponents.spacer());
        metaRow.setAlignment(Pos.CENTER_LEFT);
        if (activeConversation != null
                && activeConversation.getType() == Conversation.TypeEnum.DIRECT && !deleted) {
            MenuButton menu = new MenuButton("⋯");
            menu.getStyleClass().add("icon-button");
            menu.setAccessibleText(i18n.get("shell.messageActions"));
            MenuItem hide = new MenuItem(i18n.get("shell.deleteForMe"));
            hide.setOnAction(event -> deleteMessage(message.id(), false));
            menu.getItems().add(hide);
            if (mine) {
                MenuItem everyone = new MenuItem(i18n.get("shell.deleteForEveryone"));
                everyone.setOnAction(event -> deleteMessage(message.id(), true));
                menu.getItems().add(everyone);
            }
            metaRow.getChildren().add(menu);
        }
        VBox bubble = new VBox(3, metaRow, content);
        bubble.getStyleClass().addAll("message-bubble", mine ? "mine" : "theirs");
        bubble.setMaxWidth(600);
        HBox row = new HBox(bubble);
        row.getStyleClass().add("message-row");
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private Node pendingRow(PendingMessage pending) {
        Label meta = new Label(i18n.get("shell.sending"));
        meta.getStyleClass().add("message-meta");
        Label content = new Label(pending.content());
        content.setWrapText(true);
        content.setMaxWidth(560);
        VBox bubble = new VBox(3, meta, content);
        bubble.getStyleClass().addAll("message-bubble", "mine", "pending");
        bubble.setMaxWidth(600);
        HBox row = new HBox(bubble);
        row.getStyleClass().add("message-row");
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private Node daySeparator(LocalDate day) {
        Label label = new Label(dayFormat.format(day));
        label.getStyleClass().add("day-separator");
        HBox row = new HBox(label);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("message-row");
        return row;
    }

    private void refreshSearch(String query) {
        if (context.cache() == null) return;
        boolean searching = query != null && !query.isBlank();
        searchResults.setVisible(searching);
        searchResults.setManaged(searching);
        searchResults.setItems(FXCollections.observableArrayList(
                searching ? context.cache().search(query) : List.of()));
    }

    private void revealSearchResult(MessageSearchResult result) {
        if (result == null) return;
        conversationList.getItems().stream()
                .filter(conversation -> conversation.getId().equals(result.conversationId()))
                .findFirst()
                .ifPresent(conversation -> {
                    conversationList.getSelectionModel().select(conversation);
                    openConversation(conversation, result.messageId());
                });
    }

    private void sendMessage() {
        Conversation current = activeConversation;
        String content = composeField.getText();
        if (current == null || content == null || content.isBlank()) return;
        composeField.clear();
        String text = content;
        if (current.getType() == Conversation.TypeEnum.GROUP) {
            context.runAsync(() -> {
                context.groups().sendGroupMessage(current.getId(), text);
                context.groups().syncGroup(current.getId());
                return null;
            }, ignored -> refreshVisibleMessages(null), this::showStatusError);
            return;
        }
        try {
            context.messaging().queueMessage(current.getId(), text);
            refreshVisibleMessages(null);
        } catch (Exception exception) {
            showStatusError(exception);
            return;
        }
        context.runAsync(() -> {
            context.messaging().flushPendingMessages();
            context.messaging().syncConversation(current.getId());
            return null;
        }, ignored -> refreshVisibleMessages(null),
                exception -> status(i18n.get("shell.savedLocally"), false));
    }

    private void showFriendsDialog() {
        if (context.session() == null) return;
        status(i18n.get("shell.loadingFriends"), false);
        context.runAsync(this::listFriends, friends -> {
            List<UserPickerItem> items = friends.stream().map(this::item).toList();
            pickUsers(i18n.get("dialog.friendsTitle"), i18n.get("dialog.friendsDescription"),
                    false, false, items).ifPresent(result ->
                    openDirectChat(result.items().getFirst().id()));
        }, this::showStatusError);
    }

    private void openDirectChat(UUID participantId) {
        status(i18n.get("shell.openingChat"), false);
        context.runAsync(() -> context.session().api().createDirectConversation(
                        new CreateDirectConversationRequest().participantId(participantId)),
                conversation -> {
                    status(i18n.get("shell.connected"), true);
                    openConversation(conversation, null);
                    refreshConversations();
                }, this::showStatusError);
    }

    private void showCreateGroupDialog() {
        if (context.groups() == null) return;
        status(i18n.get("shell.loadingFriends"), false);
        context.runAsync(this::listFriends, friends -> {
            List<UserPickerItem> items = friends.stream().map(this::item).toList();
            pickUsers(i18n.get("dialog.newGroupTitle"), i18n.get("dialog.newGroupDescription"),
                    true, true, items).ifPresent(result -> {
                        if (result.name().isBlank() || result.items().isEmpty()) {
                            status(i18n.get("shell.chooseGroupName"), false);
                            return;
                        }
                        status(i18n.get("shell.creatingGroup"), false);
                        context.runAsync(() -> context.groups().createGroup(result.name(),
                                        result.items().stream().map(UserPickerItem::id).toList()),
                                created -> {
                                    status(i18n.get("shell.groupCreated"), true);
                                    loadConversations();
                                }, this::showStatusError);
                    });
        }, this::showStatusError);
    }

    private void showAddMemberDialog() {
        Conversation current = activeConversation;
        if (context.groups() == null || current == null
                || current.getType() != Conversation.TypeEnum.GROUP) {
            status(i18n.get("shell.openGroupFirst"), false);
            return;
        }
        status(i18n.get("shell.loadingFriends"), false);
        context.runAsync(() -> {
            Set<UUID> members = memberIds(current.getId());
            return listFriends().stream()
                    .filter(friend -> !members.contains(friend.getId())).toList();
        }, friends -> pickUsers(i18n.get("dialog.addMemberTitle"),
                i18n.get("dialog.addMemberDescription"), false, false,
                friends.stream().map(this::item).toList()).ifPresent(result -> {
                    UserPickerItem selected = result.items().getFirst();
                    status(i18n.get("shell.addingMember"), false);
                    context.runAsync(() -> {
                        context.groups().addMember(current.getId(), selected.id());
                        context.groups().syncGroup(current.getId());
                        return null;
                    }, ignored -> {
                        status(i18n.get("shell.memberAdded"), true);
                        refreshVisibleMessages(null);
                    }, this::showStatusError);
                }), this::showStatusError);
    }

    private void showRemoveMemberDialog() {
        Conversation current = activeConversation;
        if (context.groups() == null || current == null
                || current.getType() != Conversation.TypeEnum.GROUP) {
            status(i18n.get("shell.openGroupFirst"), false);
            return;
        }
        status(i18n.get("shell.loadingFriends"), false);
        context.runAsync(() -> {
            List<GroupMember> members = new ArrayList<>();
            String cursor = null;
            do {
                var page = context.session().api().listGroupMembers(current.getId(), cursor, 100);
                if (page == null || page.getItems() == null) break;
                page.getItems().stream()
                        .filter(member -> !context.userId().equals(member.getUser().getId()))
                        .forEach(members::add);
                String next = page.getNextCursor();
                cursor = next == null || next.equals(cursor) ? null : next;
            } while (cursor != null);
            return members;
        }, members -> pickUsers(i18n.get("dialog.removeMemberTitle"),
                i18n.get("dialog.removeMemberDescription"), false, false,
                members.stream().map(this::item).toList()).ifPresent(result -> {
                    UserPickerItem selected = result.items().getFirst();
                    status(i18n.get("shell.removingMember"), false);
                    context.runAsync(() -> {
                        context.groups().removeMember(current.getId(), selected.id());
                        context.groups().syncGroup(current.getId());
                        return null;
                    }, ignored -> {
                        status(i18n.get("shell.memberRemoved"), true);
                        refreshVisibleMessages(null);
                    }, this::showStatusError);
                }), this::showStatusError);
    }

    private Optional<PickResult> pickUsers(String title, String description, boolean askName,
                                           boolean multiple, List<UserPickerItem> items) {
        if (items.isEmpty()) {
            status(i18n.get("dialog.noCandidates"), false);
            return Optional.empty();
        }
        UserPickerController picker = new UserPickerController(i18n);
        Parent content = ViewLoader.load(ViewLoader.USER_PICKER, picker, i18n);
        picker.configure(title, description, askName, i18n.get("dialog.groupName"), items, multiple);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(context.stage());
        dialog.setTitle(title);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        ButtonType confirm = new ButtonType(i18n.get("dialog.confirm"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(i18n.get("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, cancel);
        Node confirmButton = dialog.getDialogPane().lookupButton(confirm);
        if (confirmButton != null) confirmButton.getStyleClass().add("primary-button");
        Node cancelButton = dialog.getDialogPane().lookupButton(cancel);
        if (cancelButton != null) cancelButton.getStyleClass().add("ghost-button");
        context.themeManager().apply(dialog.getDialogPane());
        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != confirm) {
            status(i18n.get("shell.connected"), true);
            return Optional.empty();
        }
        List<UserPickerItem> selected = picker.selectedItems();
        if (selected.isEmpty()) {
            status(i18n.get("dialog.chooseAtLeastOne"), false);
            return Optional.empty();
        }
        if (askName && picker.name().isBlank()) {
            status(i18n.get("dialog.chooseGroupName"), false);
            return Optional.empty();
        }
        return Optional.of(new PickResult(selected, picker.name()));
    }

    private void showRecoveryCode() {
        if (context.groups() == null) return;
        TextArea code = new TextArea(context.groups().recoveryCode());
        code.setEditable(false);
        code.setWrapText(true);
        code.setPrefRowCount(3);
        Label body = new Label(i18n.get("shell.recoveryBody"));
        body.setWrapText(true);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(context.stage());
        alert.setTitle(i18n.get("shell.recoveryTitle"));
        alert.setHeaderText(i18n.get("shell.recoveryHeader"));
        alert.getDialogPane().setContent(new VBox(8, body, code));
        ButtonType close = new ButtonType(i18n.get("dialog.close"),
                ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(close);
        Node closeButton = alert.getDialogPane().lookupButton(close);
        if (closeButton != null) closeButton.getStyleClass().add("primary-button");
        context.themeManager().apply(alert.getDialogPane());
        alert.showAndWait();
    }

    private void showAccount() {
        if (context.session() == null) return;
        Stage window = new Stage();
        window.initOwner(context.stage());
        window.initModality(Modality.WINDOW_MODAL);
        window.setTitle(i18n.get("account.title"));
        AccountController controller = new AccountController(context,
                this::openDirectChat, this::showReportForUser, this::refreshProfileHeader,
                window::close);
        Parent root = ViewLoader.load(ViewLoader.ACCOUNT, controller, i18n);
        Scene scene = new Scene(root, 960, 720);
        context.themeManager().apply(scene);
        window.setScene(scene);
        window.setMinWidth(760);
        window.setMinHeight(560);
        window.setOnHidden(event -> accountButton.requestFocus());
        window.show();
    }

    private void showGroupSettings() {
        Conversation current = activeConversation;
        if (current == null || current.getType() != Conversation.TypeEnum.GROUP) {
            status(i18n.get("shell.openGroupFirst"), false);
            return;
        }
        Stage window = new Stage();
        window.initOwner(context.stage());
        window.initModality(Modality.WINDOW_MODAL);
        window.setTitle(i18n.get("groupSettings.title"));
        GroupSettingsController controller = new GroupSettingsController(context, current,
                () -> {
                    refreshConversations();
                    chatTitleLabel.setText(current.getName());
                },
                () -> {
                    conversationList.getItems().remove(current);
                    activeConversation = null;
                    setConversationActionsDisabled(true);
                    showEmptyChat();
                    loadConversations();
                }, window::close);
        Parent root = ViewLoader.load(ViewLoader.GROUP_SETTINGS, controller, i18n);
        Scene scene = new Scene(root, 640, 600);
        context.themeManager().apply(scene);
        window.setScene(scene);
        window.setOnHidden(event -> groupSettingsButton.requestFocus());
        window.show();
    }

    private void reportActiveConversation() {
        Conversation current = activeConversation;
        if (current == null || current.getType() != Conversation.TypeEnum.DIRECT) {
            status(i18n.get("shell.reportDirectOnly"), false);
            return;
        }
        UUID otherId = otherParticipant(current.getId());
        if (otherId == null) {
            status(i18n.get("shell.noReportTarget"), false);
            return;
        }
        User target = new User().id(otherId).username(otherId.toString())
                .fullName(current.getName() == null || current.getName().isBlank()
                        ? otherId.toString() : current.getName());
        showReportForUser(target);
    }

    private void showReportForUser(User target) {
        if (context.cache() == null) return;
        List<CachedMessage> evidence = activeConversation == null
                || activeConversation.getType() != Conversation.TypeEnum.DIRECT
                ? List.of()
                : context.cache().messagesInConversation(activeConversation.getId()).stream()
                        .filter(message -> target.getId().toString().equals(message.senderName()))
                        .filter(message -> message.deletedForEveryoneAt() == null)
                        .toList();
        Stage window = new Stage();
        window.initOwner(context.stage());
        window.initModality(Modality.WINDOW_MODAL);
        window.setTitle(i18n.get("report.title"));
        ReportController controller = new ReportController(context, target, evidence, window::close);
        Parent root = ViewLoader.load(ViewLoader.REPORT, controller, i18n);
        Scene scene = new Scene(root, 560, 620);
        context.themeManager().apply(scene);
        window.setScene(scene);
        window.show();
    }

    private UUID otherParticipant(UUID conversationId) {
        if (context.cache() == null || context.userId() == null) return null;
        return context.cache().messagesInConversation(conversationId).stream()
                .map(CachedMessage::senderName)
                .filter(sender -> !context.userId().toString().equals(sender))
                .map(sender -> {
                    try {
                        return UUID.fromString(sender);
                    } catch (IllegalArgumentException exception) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private void deleteMessage(UUID messageId, boolean forEveryone) {
        Conversation current = activeConversation;
        if (current == null || context.messaging() == null) return;
        if (current.getType() != Conversation.TypeEnum.DIRECT) {
            status(i18n.get("shell.deleteDirectOnly"), false);
            return;
        }
        if (forEveryone && !ConfirmDialog.show(context.stage(), context.themeManager(),
                i18n.get("shell.deleteEveryoneTitle"), i18n.get("shell.deleteEveryoneMessage"),
                i18n.get("shell.deleteForEveryone"), i18n.get("dialog.cancel"), true)) {
            return;
        }
        status(i18n.get("shell.deletingMessage"), false);
        context.runAsync(() -> {
            if (forEveryone) {
                context.messaging().deleteMessageForEveryone(current.getId(), messageId);
            } else {
                context.messaging().hideMessageForMe(current.getId(), messageId);
            }
            return null;
        }, ignored -> {
            refreshVisibleMessages(null);
            status(i18n.get("shell.messageDeletedStatus"), true);
        }, this::showStatusError);
    }

    private void clearHistory() {
        Conversation current = activeConversation;
        if (current == null || context.messaging() == null) return;
        if (current.getType() != Conversation.TypeEnum.DIRECT) {
            status(i18n.get("shell.clearDirectOnly"), false);
            return;
        }
        if (!ConfirmDialog.show(context.stage(), context.themeManager(),
                i18n.get("shell.clearHistoryTitle"), i18n.get("shell.clearHistoryMessage"),
                i18n.get("shell.clearHistory"), i18n.get("dialog.cancel"), true)) {
            return;
        }
        status(i18n.get("shell.clearingHistory"), false);
        context.runAsync(() -> {
            context.messaging().clearHistory(current.getId());
            return null;
        }, ignored -> {
            refreshVisibleMessages(null);
            status(i18n.get("shell.historyCleared"), true);
        }, this::showStatusError);
    }

    private void setConversationActionsDisabled(boolean disabled) {
        reportButton.setDisable(disabled);
        groupSettingsButton.setDisable(disabled);
        clearHistoryButton.setDisable(disabled);
    }

    private void refreshProfileHeader() {
        String name = displayName();
        profileNameLabel.setText(name);
        profileAvatarHost.getChildren().setAll(UiComponents.avatar(name, 38));
        profileAvatarHost.setAccessibleText(name);
    }

    private List<User> listFriends() throws ApiException {
        List<User> friends = new ArrayList<>();
        String cursor = null;
        do {
            var page = context.session().api().listFriends(cursor, 100);
            if (page == null || page.getItems() == null) break;
            friends.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return friends;
    }

    private Set<UUID> memberIds(UUID groupId) throws ApiException {
        Set<UUID> members = new HashSet<>();
        String cursor = null;
        do {
            var page = context.session().api().listGroupMembers(groupId, cursor, 100);
            if (page == null || page.getItems() == null) break;
            page.getItems().forEach(member -> members.add(member.getUser().getId()));
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return members;
    }

    private UserPickerItem item(User user) {
        String title = user.getFullName() == null || user.getFullName().isBlank()
                ? user.getUsername() : user.getFullName();
        boolean online = user.getPresence() == User.PresenceEnum.ONLINE;
        String presence = i18n.get(online ? "common.online" : "common.offline");
        return new UserPickerItem(user.getId(), title, "@" + user.getUsername() + " · " + presence, online);
    }

    private UserPickerItem item(GroupMember member) {
        User user = member.getUser();
        String title = user.getFullName() == null || user.getFullName().isBlank()
                ? user.getUsername() : user.getFullName();
        String rawRole = member.getRole() == null ? "" : member.getRole().getValue();
        String role = "admin".equalsIgnoreCase(rawRole)
                ? i18n.get("dialog.roleAdmin") : i18n.get("dialog.roleMember");
        return new UserPickerItem(user.getId(), title,
                i18n.format("dialog.memberMeta", role, member.getJoinedEpoch()), true);
    }

    private void startRealtime() {
        context.startRealtime(event -> context.network().execute(() -> {
            try {
                boolean mlsEvent = context.groups().handleRealtimeEvent(event);
                if (!mlsEvent) context.messaging().acceptRealtimeEvent(event);
                Platform.runLater(() -> {
                    refreshVisibleMessages(null);
                    if (mlsEvent) refreshConversations();
                });
            } catch (Exception exception) {
                Platform.runLater(() -> status(i18n.get("shell.realtimeResync"), false));
            }
        }), () -> context.network().execute(() -> {
            try {
                context.messaging().flushPendingMessages();
                context.messaging().syncAllDirectConversations();
                syncAllGroups();
                Platform.runLater(() -> {
                    refreshConversations();
                    refreshVisibleMessages(null);
                    status(i18n.get("shell.connected"), true);
                });
            } catch (Exception exception) {
                Platform.runLater(() -> status(i18n.get("shell.reconnectRetry"), false));
            }
        }));
    }

    private void syncAllGroups() throws Exception {
        syncGroups(listConversations());
    }

    private void syncGroups(List<Conversation> all) {
        for (Conversation conversation : all) {
            if (conversation.getType() != Conversation.TypeEnum.GROUP) continue;
            try {
                context.groups().syncGroup(conversation.getId());
            } catch (Exception ignored) {
                // One group awaiting a commit must not block the others.
            }
        }
    }

    private void showEmptyChat() {
        chatTitleLabel.setText(i18n.get("shell.noConversationTitle"));
        chatSubtitleLabel.setText(i18n.get("shell.noConversationHint"));
        messageRows.getChildren().setAll(UiComponents.emptyState(
                i18n.get("shell.noConversationTitle"),
                i18n.get("shell.noConversationHint"), null, null));
    }

    private void status(String text, boolean online) {
        statusLabel.setText(text);
        statusDot.getStyleClass().setAll("presence-dot", online ? "online" : "offline");
    }

    private void showStatusError(Exception exception) {
        status(UserErrors.statusMessage(exception, i18n), false);
    }

    private void toggleTheme() {
        context.themeManager().toggle();
        Scene scene = themeButton.getScene();
        if (scene != null) context.themeManager().apply(scene);
        updateThemeButton();
    }

    private void updateThemeButton() {
        boolean dark = context.themeManager().isDark();
        themeButton.setText(dark ? "☀" : "☾");
        themeButton.setAccessibleText(i18n.get(dark ? "theme.toLight" : "theme.toDark"));
    }

    private void signOut() {
        status(i18n.get("shell.signingOut"), false);
        context.signOut(onSignedOut);
    }

    private static String preview(String text) {
        if (text == null) return "";
        return text.length() > 110 ? text.substring(0, 107) + "…" : text;
    }

    private record PickResult(List<UserPickerItem> items, String name) {
    }

    private final class ConversationCell extends ListCell<Conversation> {
        @Override
        protected void updateItem(Conversation item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String name = item.getName() == null || item.getName().isBlank()
                    ? i18n.get("shell.directChat") : item.getName();
            String meta = item.getType() == Conversation.TypeEnum.GROUP
                    ? i18n.get("shell.groupChat") : i18n.get("shell.directChat");
            Label nameLabel = new Label(name);
            nameLabel.getStyleClass().add("conversation-name");
            Label metaLabel = new Label(meta);
            metaLabel.getStyleClass().add("conversation-meta");
            VBox text = new VBox(2, nameLabel, metaLabel);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10, UiComponents.avatar(name, 34), text);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }
    }

    private final class SearchResultCell extends ListCell<MessageSearchResult> {
        @Override
        protected void updateItem(MessageSearchResult item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String sender = context.userId() != null
                    && context.userId().toString().equals(item.senderName())
                    ? i18n.get("shell.messageYou") : item.senderName();
            Label title = new Label(sender + ": " + preview(item.preview()));
            title.getStyleClass().add("conversation-name");
            Label meta = new Label(messageTime.format(item.createdAt()));
            meta.getStyleClass().add("conversation-meta");
            setGraphic(new VBox(2, title, meta));
            setText(null);
        }
    }
}
