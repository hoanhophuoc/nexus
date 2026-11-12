package com.nexus.desktop.user;

import com.nexus.desktop.shared.ui.ConfirmDialog;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.model.DecideFriendRequestRequest;
import com.nexus.sdk.model.FriendRequest;
import com.nexus.sdk.model.MyProfile;
import com.nexus.sdk.model.OutgoingFriendRequest;
import com.nexus.sdk.model.ProfileUpdate;
import com.nexus.sdk.model.SendFriendRequestRequest;
import com.nexus.sdk.model.User;
import com.nexus.sdk.model.UserPage;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** FXML controller for the signed-in account, security and social workspace. */
public final class AccountController {
    private final UserAppContext context;
    private final I18n i18n;
    private final Consumer<UUID> onOpenChat;
    private final Consumer<User> onReport;
    private final Runnable onProfileChanged;
    private final Runnable onClose;

    @FXML private StackPane accountAvatarHost;
    @FXML private Label accountNameLabel;
    @FXML private Label accountUsernameLabel;
    @FXML private Button closeButton;
    @FXML private TabPane tabs;
    @FXML private Label profileFullNameLabel;
    @FXML private Label profileEmailLabel;
    @FXML private Label profileAddressLabel;
    @FXML private Label profileBirthDateLabel;
    @FXML private Label profileGenderLabel;
    @FXML private TextField profileFullNameField;
    @FXML private TextField profileEmailField;
    @FXML private TextField profileAddressField;
    @FXML private DatePicker profileBirthDateField;
    @FXML private ComboBox<ProfileGender> profileGenderField;
    @FXML private Button saveProfileButton;
    @FXML private Label currentPasswordLabel;
    @FXML private Label newPasswordLabel;
    @FXML private Label confirmPasswordLabel;
    @FXML private PasswordField currentPasswordField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button changePasswordButton;
    @FXML private TextField discoveryQueryField;
    @FXML private Button discoverySearchButton;
    @FXML private ListView<User> discoveryList;
    @FXML private Button sendRequestButton;
    @FXML private Button messageUserButton;
    @FXML private ListView<FriendRequest> incomingList;
    @FXML private Button acceptRequestButton;
    @FXML private Button declineRequestButton;
    @FXML private ListView<OutgoingFriendRequest> outgoingList;
    @FXML private Button cancelRequestButton;
    @FXML private ListView<User> friendsList;
    @FXML private Button messageFriendButton;
    @FXML private Button unfriendButton;
    @FXML private Button blockButton;
    @FXML private Button reportButton;
    @FXML private ListView<User> blockedList;
    @FXML private Button unblockButton;
    @FXML private Label statusLabel;

    AccountController(UserAppContext context, Consumer<UUID> onOpenChat, Consumer<User> onReport,
                      Runnable onProfileChanged, Runnable onClose) {
        this.context = context;
        this.i18n = context.i18n();
        this.onOpenChat = onOpenChat;
        this.onReport = onReport;
        this.onProfileChanged = onProfileChanged;
        this.onClose = onClose;
    }

    @FXML
    private void initialize() {
        profileFullNameLabel.setLabelFor(profileFullNameField);
        profileEmailLabel.setLabelFor(profileEmailField);
        profileAddressLabel.setLabelFor(profileAddressField);
        profileBirthDateLabel.setLabelFor(profileBirthDateField);
        profileGenderLabel.setLabelFor(profileGenderField);
        currentPasswordLabel.setLabelFor(currentPasswordField);
        newPasswordLabel.setLabelFor(newPasswordField);
        confirmPasswordLabel.setLabelFor(confirmPasswordField);
        profileFullNameField.setAccessibleText(i18n.get("account.fullName"));
        profileEmailField.setAccessibleText(i18n.get("account.email"));
        profileAddressField.setAccessibleText(i18n.get("account.address"));
        profileBirthDateField.setAccessibleText(i18n.get("account.birthDate"));
        profileGenderField.setAccessibleText(i18n.get("account.gender"));
        currentPasswordField.setAccessibleText(i18n.get("account.currentPassword"));
        newPasswordField.setAccessibleText(i18n.get("account.newPassword"));
        confirmPasswordField.setAccessibleText(i18n.get("account.confirmPassword"));
        discoveryQueryField.setAccessibleText(i18n.get("account.searchPrompt"));
        discoveryList.setAccessibleText(i18n.get("account.discoverTitle"));
        incomingList.setAccessibleText(i18n.get("account.incomingTitle"));
        outgoingList.setAccessibleText(i18n.get("account.outgoingTitle"));
        friendsList.setAccessibleText(i18n.get("account.friendsTitle"));
        blockedList.setAccessibleText(i18n.get("account.blockedTitle"));
        profileGenderField.getItems().setAll(List.of(
                new ProfileGender(i18n.get("register.gender.female"), ProfileUpdate.GenderEnum.FEMALE),
                new ProfileGender(i18n.get("register.gender.male"), ProfileUpdate.GenderEnum.MALE),
                new ProfileGender(i18n.get("register.gender.other"), ProfileUpdate.GenderEnum.OTHER),
                new ProfileGender(i18n.get("register.gender.undisclosed"), ProfileUpdate.GenderEnum.UNDISCLOSED)));
        discoveryList.setCellFactory(ignored -> userCell());
        friendsList.setCellFactory(ignored -> userCell());
        blockedList.setCellFactory(ignored -> userCell());
        incomingList.setCellFactory(ignored -> incomingCell());
        outgoingList.setCellFactory(ignored -> outgoingCell());
        setPlaceholders();
        closeButton.setOnAction(event -> onClose.run());
        saveProfileButton.setOnAction(event -> saveProfile());
        changePasswordButton.setOnAction(event -> changePassword());
        discoverySearchButton.setOnAction(event -> search());
        discoveryQueryField.setOnAction(event -> search());
        sendRequestButton.setOnAction(event -> sendRequest());
        messageUserButton.setOnAction(event -> openSelected(discoveryList));
        acceptRequestButton.setOnAction(event -> decide(true));
        declineRequestButton.setOnAction(event -> decide(false));
        cancelRequestButton.setOnAction(event -> cancelRequest());
        messageFriendButton.setOnAction(event -> openSelected(friendsList));
        unfriendButton.setOnAction(event -> unfriend());
        blockButton.setOnAction(event -> block());
        reportButton.setOnAction(event -> report());
        unblockButton.setOnAction(event -> unblock());
        showProfile(context.profile());
        loadAll();
    }

    private void setPlaceholders() {
        discoveryList.setPlaceholder(UiComponents.emptyState(i18n.get("account.noResults"),
                i18n.get("account.noResultsHint"), null, null));
        incomingList.setPlaceholder(UiComponents.emptyState(i18n.get("account.noIncoming"),
                i18n.get("account.noIncomingHint"), null, null));
        outgoingList.setPlaceholder(UiComponents.emptyState(i18n.get("account.noOutgoing"),
                i18n.get("account.noOutgoingHint"), null, null));
        friendsList.setPlaceholder(UiComponents.emptyState(i18n.get("account.noFriends"),
                i18n.get("account.noFriendsHint"), null, null));
        blockedList.setPlaceholder(UiComponents.emptyState(i18n.get("account.noBlocked"),
                i18n.get("account.noBlockedHint"), null, null));
    }

    private void loadAll() {
        status(i18n.get("account.loading"));
        List<ListView<?>> lists = List.of(discoveryList, incomingList, outgoingList, friendsList, blockedList);
        lists.forEach(list -> list.setPlaceholder(UiComponents.loadingState(i18n.get("account.loading"))));
        context.runAsync(() -> new Snapshot(listUsers(), listIncoming(), listOutgoing(), listBlocked()),
                this::applySnapshot, this::loadFailed);
    }

    private void applySnapshot(Snapshot snapshot) {
        friendsList.setItems(FXCollections.observableArrayList(snapshot.friends()));
        incomingList.setItems(FXCollections.observableArrayList(snapshot.incoming()));
        outgoingList.setItems(FXCollections.observableArrayList(snapshot.outgoing()));
        blockedList.setItems(FXCollections.observableArrayList(snapshot.blocked()));
        setPlaceholders();
        showProfile(context.profile());
        status(i18n.get("account.ready"));
    }

    private void loadFailed(Exception exception) {
        String message = UserErrors.statusMessage(exception, i18n);
        status(message);
        List<ListView<?>> lists = List.of(discoveryList, incomingList, outgoingList, friendsList, blockedList);
        lists.forEach(list -> list.setPlaceholder(UiComponents.errorState(
                i18n.get("error.title"), message, i18n.get("shell.retry"), this::loadAll)));
    }

    private void showProfile(MyProfile profile) {
        if (profile == null) return;
        String name = profile.getFullName() == null || profile.getFullName().isBlank()
                ? profile.getUsername() : profile.getFullName();
        accountNameLabel.setText(name);
        accountUsernameLabel.setText("@" + profile.getUsername() + " · " + profile.getEmail());
        accountAvatarHost.getChildren().setAll(UiComponents.avatar(name, 44));
        accountAvatarHost.setAccessibleText(name);
        profileFullNameField.setText(profile.getFullName());
        profileEmailField.setText(profile.getEmail());
        profileAddressField.setText(profile.getAddress());
        profileBirthDateField.setValue(profile.getBirthDate());
        selectGender(profile.getGender());
    }

    private void selectGender(MyProfile.GenderEnum gender) {
        if (gender == null) return;
        for (ProfileGender choice : profileGenderField.getItems()) {
            if (choice.value().getValue().equals(gender.getValue())) {
                profileGenderField.setValue(choice);
                return;
            }
        }
    }

    private void saveProfile() {
        String fullName = text(profileFullNameField);
        String email = text(profileEmailField);
        if (fullName.isBlank() || email.isBlank() || !email.contains("@")) {
            status(i18n.get("account.profileRequired"));
            return;
        }
        ProfileGender gender = profileGenderField.getValue();
        LocalDate birthDate = profileBirthDateField.getValue();
        ProfileUpdate update = new ProfileUpdate().fullName(fullName).email(email)
                .address(text(profileAddressField)).birthDate(birthDate);
        if (gender != null) update.gender(gender.value());
        status(i18n.get("account.savingProfile"));
        saveProfileButton.setDisable(true);
        context.runAsync(() -> context.updateProfile(update), profile -> {
            saveProfileButton.setDisable(false);
            showProfile(profile);
            onProfileChanged.run();
            status(i18n.get("account.profileSaved"));
        }, exception -> {
            saveProfileButton.setDisable(false);
            fail(exception);
        });
    }

    private void changePassword() {
        String current = currentPasswordField.getText();
        String next = newPasswordField.getText();
        String confirmation = confirmPasswordField.getText();
        if (current == null || current.isBlank()) {
            status(i18n.get("account.currentRequired"));
            return;
        }
        if (next == null || next.length() < 12) {
            status(i18n.get("account.passwordShort"));
            return;
        }
        if (!next.equals(confirmation)) {
            status(i18n.get("account.passwordMismatch"));
            return;
        }
        status(i18n.get("account.savingPassword"));
        changePasswordButton.setDisable(true);
        context.runAsync(() -> {
            context.session().api().changePassword(
                    new com.nexus.sdk.model.ChangePasswordRequest()
                            .currentPassword(current).newPassword(next));
            return null;
        }, ignored -> {
            changePasswordButton.setDisable(false);
            currentPasswordField.clear();
            newPasswordField.clear();
            confirmPasswordField.clear();
            status(i18n.get("account.passwordChanged"));
        }, exception -> {
            changePasswordButton.setDisable(false);
            if (exception instanceof ApiException api
                    && (api.getCode() == 400 || api.getCode() == 401)) {
                statusError(i18n.get("account.currentPasswordWrong"));
            } else {
                fail(exception);
            }
        });
    }

    private void search() {
        String query = text(discoveryQueryField);
        if (query.isBlank()) {
            discoveryList.setItems(FXCollections.observableArrayList());
            status(i18n.get("account.searchRequired"));
            return;
        }
        status(i18n.get("account.searching"));
        discoveryList.setPlaceholder(UiComponents.loadingState(i18n.get("account.searching")));
        context.runAsync(() -> searchUsers(query), users -> {
            discoveryList.setItems(FXCollections.observableArrayList(users));
            status(users.isEmpty() ? i18n.get("account.noResults") : i18n.format("account.results", users.size()));
        }, exception -> {
            discoveryList.setPlaceholder(UiComponents.errorState(i18n.get("error.title"),
                    UserErrors.statusMessage(exception, i18n), i18n.get("shell.retry"), this::search));
            fail(exception);
        });
    }

    private void sendRequest() {
        User selected = discoveryList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.choosePerson"));
            return;
        }
        status(i18n.get("account.sendingRequest"));
        context.runAsync(() -> {
            context.session().api().sendFriendRequest(
                    new SendFriendRequestRequest().userId(selected.getId()));
            return null;
        }, ignored -> {
            status(i18n.format("account.requestSent", name(selected)));
            refreshRequests();
        }, exception -> {
            if (exception instanceof ApiException api && api.getCode() == 409) {
                statusError(i18n.format("account.requestConflict", name(selected)));
            } else {
                fail(exception);
            }
        });
    }

    private void decide(boolean accept) {
        FriendRequest selected = incomingList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.chooseRequest"));
            return;
        }
        status(i18n.get(accept ? "account.accepting" : "account.declining"));
        context.runAsync(() -> {
            context.session().api().decideFriendRequest(selected.getId(),
                    new DecideFriendRequestRequest().decision(accept
                            ? DecideFriendRequestRequest.DecisionEnum.ACCEPT
                            : DecideFriendRequestRequest.DecisionEnum.DECLINE));
            return null;
        }, ignored -> {
            status(i18n.get(accept ? "account.accepted" : "account.declined"));
            loadAll();
        }, this::fail);
    }

    private void cancelRequest() {
        OutgoingFriendRequest selected = outgoingList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.chooseOutgoing"));
            return;
        }
        status(i18n.get("account.cancelling"));
        context.runAsync(() -> {
            context.session().api().cancelFriendRequest(selected.getId());
            return null;
        }, ignored -> {
            status(i18n.get("account.cancelled"));
            refreshRequests();
        }, this::fail);
    }

    private void unfriend() {
        User selected = friendsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.chooseFriend"));
            return;
        }
        if (!confirm(i18n.get("account.unfriendTitle"),
                i18n.format("account.unfriendMessage", name(selected)),
                i18n.get("account.unfriend"))) return;
        status(i18n.get("account.removingFriend"));
        context.runAsync(() -> {
            context.session().api().removeFriend(selected.getId());
            return null;
        }, ignored -> {
            status(i18n.format("account.friendRemoved", name(selected)));
            loadAll();
        }, this::fail);
    }

    private void block() {
        User selected = friendsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.chooseFriend"));
            return;
        }
        if (!confirm(i18n.get("account.blockTitle"),
                i18n.format("account.blockMessage", name(selected)),
                i18n.get("account.block"))) return;
        status(i18n.get("account.blocking"));
        context.runAsync(() -> {
            context.session().api().blockUser(selected.getId());
            return null;
        }, ignored -> {
            status(i18n.format("account.blocked", name(selected)));
            loadAll();
        }, this::fail);
    }

    private void unblock() {
        User selected = blockedList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.chooseBlocked"));
            return;
        }
        status(i18n.get("account.unblocking"));
        context.runAsync(() -> {
            context.session().api().unblockUser(selected.getId());
            return null;
        }, ignored -> {
            status(i18n.format("account.unblocked", name(selected)));
            loadAll();
        }, this::fail);
    }

    private void report() {
        User selected = friendsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.chooseFriend"));
            return;
        }
        onReport.accept(selected);
    }

    private void openSelected(ListView<User> list) {
        User selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status(i18n.get("account.choosePerson"));
            return;
        }
        onOpenChat.accept(selected.getId());
        onClose.run();
    }

    private void refreshRequests() {
        context.runAsync(() -> new RequestSnapshot(listIncoming(), listOutgoing()),
                snapshot -> {
                    incomingList.setItems(FXCollections.observableArrayList(snapshot.incoming()));
                    outgoingList.setItems(FXCollections.observableArrayList(snapshot.outgoing()));
                    setPlaceholders();
                }, this::fail);
    }

    private List<User> listUsers() throws ApiException {
        List<User> users = new ArrayList<>();
        String cursor = null;
        do {
            UserPage page = context.session().api().listFriends(cursor, 100);
            if (page == null || page.getItems() == null) break;
            users.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return users;
    }

    private List<User> searchUsers(String query) throws ApiException {
        List<User> users = new ArrayList<>();
        String cursor = null;
        do {
            UserPage page = context.session().api().searchUsers(query, cursor, 50);
            if (page == null || page.getItems() == null) break;
            users.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return users;
    }

    private List<User> listBlocked() throws ApiException {
        List<User> users = new ArrayList<>();
        String cursor = null;
        do {
            UserPage page = context.session().api().listBlockedUsers(cursor, 100);
            if (page == null || page.getItems() == null) break;
            users.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return users;
    }

    private List<FriendRequest> listIncoming() throws ApiException {
        List<FriendRequest> requests = new ArrayList<>();
        String cursor = null;
        do {
            var page = context.session().api().listFriendRequests(cursor, 100);
            if (page == null || page.getItems() == null) break;
            requests.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return requests;
    }

    private List<OutgoingFriendRequest> listOutgoing() throws ApiException {
        List<OutgoingFriendRequest> requests = new ArrayList<>();
        String cursor = null;
        do {
            var page = context.session().api().listOutgoingFriendRequests(cursor, 100);
            if (page == null || page.getItems() == null) break;
            requests.addAll(page.getItems());
            String next = page.getNextCursor();
            cursor = next == null || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return requests;
    }

    private boolean confirm(String title, String message, String action) {
        return ConfirmDialog.show(statusLabel.getScene() == null ? null
                        : statusLabel.getScene().getWindow(), context.themeManager(),
                title, message, action, i18n.get("dialog.cancel"), true);
    }

    private void status(String text) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().remove("status-error");
    }

    private void fail(Exception exception) {
        statusError(UserErrors.statusMessage(exception, i18n));
    }

    private void statusError(String message) {
        statusLabel.setText(message);
        if (!statusLabel.getStyleClass().contains("status-error")) {
            statusLabel.getStyleClass().add("status-error");
        }
    }

    private ListCell<User> userCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(User user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean online = user.getPresence() == User.PresenceEnum.ONLINE;
                setGraphic(UiComponents.personRow(UiComponents.avatar(name(user), 30),
                        name(user), "@" + user.getUsername() + " · "
                                + i18n.get(online ? "common.online" : "common.offline"),
                        online, i18n.get("common.online"), i18n.get("common.offline")));
                setText(null);
            }
        };
    }

    private ListCell<FriendRequest> incomingCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(FriendRequest request, boolean empty) {
                super.updateItem(request, empty);
                if (empty || request == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                User from = request.getFrom();
                boolean online = from.getPresence() == User.PresenceEnum.ONLINE;
                setGraphic(UiComponents.personRow(UiComponents.avatar(name(from), 30), name(from),
                        i18n.format("account.requestFrom", "@" + from.getUsername(),
                                i18n.get(online ? "common.online" : "common.offline")),
                        online, i18n.get("common.online"), i18n.get("common.offline")));
                setText(null);
            }
        };
    }

    private ListCell<OutgoingFriendRequest> outgoingCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(OutgoingFriendRequest request, boolean empty) {
                super.updateItem(request, empty);
                if (empty || request == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                User to = request.getTo();
                boolean online = to.getPresence() == User.PresenceEnum.ONLINE;
                setGraphic(UiComponents.personRow(UiComponents.avatar(name(to), 30), name(to),
                        i18n.format("account.requestTo", "@" + to.getUsername(),
                                i18n.get(online ? "common.online" : "common.offline")),
                        online, i18n.get("common.online"), i18n.get("common.offline")));
                setText(null);
            }
        };
    }

    private static String name(User user) {
        return user.getFullName() == null || user.getFullName().isBlank()
                ? user.getUsername() : user.getFullName();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().strip();
    }

    private record Snapshot(List<User> friends, List<FriendRequest> incoming,
                            List<OutgoingFriendRequest> outgoing, List<User> blocked) {
    }

    private record RequestSnapshot(List<FriendRequest> incoming,
                                   List<OutgoingFriendRequest> outgoing) {
    }

    private record ProfileGender(String label, ProfileUpdate.GenderEnum value) {
        @Override
        public String toString() {
            return label;
        }
    }
}
