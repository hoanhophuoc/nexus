package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.model.AdminSetUserLockRequest;
import com.haichutieu.chatsystem.sdk.model.AdminUser;
import com.haichutieu.chatsystem.sdk.model.AdminUserPage;
import com.haichutieu.chatsystem.sdk.model.LoginHistoryEntry;
import com.haichutieu.chatsystem.sdk.model.LoginHistoryPage;
import com.haichutieu.chatsystem.sdk.model.ProfileUpdate;
import com.haichutieu.chatsystem.sdk.model.Registration;
import com.haichutieu.chatsystem.sdk.model.User;
import com.haichutieu.chatsystem.sdk.model.UserPage;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Accounts workspace: search, create/edit/delete accounts, reset passwords and inspect history/friends. */
public final class AccountsController {
    private final AdminAppContext context;
    private final I18n i18n;
    private AdminUser selectedUser;
    private boolean creating;
    private PendingAction pendingAction;
    private AdminUser pendingUser;
    private Node confirmationFocus;

    @FXML private TextField filterField;
    @FXML private Button applyButton;
    @FXML private Button newUserButton;
    @FXML private Label statusLabel;
    @FXML private TableView<AdminUser> accountsTable;
    @FXML private TableColumn<AdminUser, String> usernameColumn;
    @FXML private TableColumn<AdminUser, String> fullNameColumn;
    @FXML private TableColumn<AdminUser, String> emailColumn;
    @FXML private TableColumn<AdminUser, String> statusColumn;
    @FXML private TableColumn<AdminUser, String> actionColumn;

    @FXML private Label detailsTitle;
    @FXML private Label detailsStatus;
    @FXML private Label usernameLabel;
    @FXML private Label fullNameLabel;
    @FXML private Label emailLabel;
    @FXML private Label addressLabel;
    @FXML private Label birthDateLabel;
    @FXML private Label genderLabel;
    @FXML private Label passwordLabel;
    @FXML private TextField usernameField;
    @FXML private TextField fullNameField;
    @FXML private TextField emailField;
    @FXML private TextField addressField;
    @FXML private DatePicker birthDateField;
    @FXML private ComboBox<GenderOption> genderField;
    @FXML private VBox passwordBox;
    @FXML private PasswordField passwordField;
    @FXML private Button saveButton;
    @FXML private Button resetPasswordButton;
    @FXML private Button lockButton;
    @FXML private Button deleteButton;
    @FXML private Button cancelButton;
    @FXML private VBox actionConfirmBox;
    @FXML private Label confirmActionLabel;
    @FXML private Button confirmActionButton;
    @FXML private Button cancelActionButton;

    @FXML private Label historyStatus;
    @FXML private TableView<LoginHistoryEntry> historyTable;
    @FXML private TableColumn<LoginHistoryEntry, String> historyWhenColumn;
    @FXML private TableColumn<LoginHistoryEntry, String> historyUserColumn;
    @FXML private Label friendStatus;
    @FXML private TableView<User> friendsTable;
    @FXML private TableColumn<User, String> friendUsernameColumn;
    @FXML private TableColumn<User, String> friendFullNameColumn;
    @FXML private TableColumn<User, String> friendAddressColumn;

    AccountsController(AdminAppContext context) {
        this.context = context;
        this.i18n = context.i18n();
    }

    @FXML
    private void initialize() {
        AdminUi.bind(usernameColumn, AdminUser::getUsername);
        AdminUi.bind(fullNameColumn, AdminUser::getFullName);
        AdminUi.bind(emailColumn, AdminUser::getEmail);
        AdminUi.bind(statusColumn, this::statusText);
        AdminUi.bindAction(actionColumn, this::actionButtons);
        AdminUi.bind(historyWhenColumn, entry -> AdminUi.time(entry.getOccurredAt(), i18n));
        AdminUi.bind(historyUserColumn, entry -> entry.getFullName() + " (" + entry.getUsername() + ")");
        AdminUi.bind(friendUsernameColumn, User::getUsername);
        AdminUi.bind(friendFullNameColumn, User::getFullName);
        AdminUi.bind(friendAddressColumn, User::getAddress);

        accountsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        friendsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        accountsTable.setAccessibleText(i18n.get("admin.accounts.title"));
        historyTable.setAccessibleText(i18n.get("admin.accounts.loginHistoryTitle"));
        friendsTable.setAccessibleText(i18n.get("admin.accounts.friendListTitle"));
        filterField.setAccessibleText(i18n.get("admin.accounts.filterPrompt"));

        usernameLabel.setLabelFor(usernameField);
        fullNameLabel.setLabelFor(fullNameField);
        emailLabel.setLabelFor(emailField);
        addressLabel.setLabelFor(addressField);
        birthDateLabel.setLabelFor(birthDateField);
        genderLabel.setLabelFor(genderField);
        passwordLabel.setLabelFor(passwordField);
        usernameField.setAccessibleText(i18n.get("admin.accounts.usernamePrompt"));
        fullNameField.setAccessibleText(i18n.get("admin.accounts.fullNamePrompt"));
        emailField.setAccessibleText(i18n.get("admin.accounts.emailPrompt"));
        addressField.setAccessibleText(i18n.get("admin.accounts.addressPrompt"));
        birthDateField.setAccessibleText(i18n.get("admin.accounts.birthDatePrompt"));
        genderField.setAccessibleText(i18n.get("admin.accounts.gender"));
        passwordField.setAccessibleText(i18n.get("admin.accounts.passwordPrompt"));
        genderField.setItems(FXCollections.observableArrayList(GenderOption.values()));
        genderField.setConverter(new StringConverter<>() {
            @Override
            public String toString(GenderOption option) {
                return option == null ? "" : i18n.get(option.key());
            }

            @Override
            public GenderOption fromString(String value) {
                return null;
            }
        });
        genderField.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(GenderOption option, boolean empty) {
                super.updateItem(option, empty);
                setText(empty || option == null ? null : i18n.get(option.key()));
            }
        });

        accountsTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (!creating && selected != null) showUser(selected);
                });
        filterField.setOnAction(event -> loadUsers(null));
        applyButton.setOnAction(event -> loadUsers(null));
        newUserButton.setOnAction(event -> beginCreate());
        saveButton.setOnAction(event -> saveUser());
        resetPasswordButton.setOnAction(event -> requestPasswordReset());
        lockButton.setOnAction(event -> requestLockToggle(selectedUser));
        deleteButton.setOnAction(event -> requestDelete());
        cancelButton.setOnAction(event -> cancelEdit());
        confirmActionButton.setOnAction(event -> confirmPendingAction());
        cancelActionButton.setOnAction(event -> hideActionConfirmation());

        AdminUi.empty(historyTable, i18n, "admin.accounts.loginHistoryEmptyTitle",
                "admin.accounts.loginHistoryEmptyHint");
        AdminUi.empty(friendsTable, i18n, "admin.accounts.friendListEmptyTitle",
                "admin.accounts.friendListEmptyHint");
        clearDetails();
        loadUsers(null);
    }

    private HBox actionButtons(AdminUser user) {
        Button edit = new Button(i18n.get("admin.accounts.edit"));
        edit.getStyleClass().add("secondary-button");
        edit.setAccessibleText(i18n.format("admin.accounts.editAccessible", user.getUsername()));
        edit.setOnAction(event -> {
            accountsTable.getSelectionModel().select(user);
            showUser(user);
            fullNameField.requestFocus();
        });
        boolean locked = user.getStatus() == AdminUser.StatusEnum.LOCKED;
        Button lock = new Button(i18n.get(locked ? "admin.accounts.unlock" : "admin.accounts.lock"));
        lock.getStyleClass().add(locked ? "secondary-button" : "danger-button");
        lock.setAccessibleText(i18n.format(locked ? "admin.accounts.unlockAccessible"
                : "admin.accounts.lockAccessible", user.getUsername()));
        lock.setOnAction(event -> requestLockToggle(user));
        HBox box = new HBox(6, edit, lock);
        box.getStyleClass().add("admin-action-row");
        return box;
    }

    private void loadUsers(UUID preferredId) {
        String filter = filterField.getText() == null ? null : filterField.getText().strip();
        UUID currentId = preferredId != null ? preferredId : selectedUser == null ? null : selectedUser.getId();
        statusLabel.setText(i18n.get("admin.accounts.loading"));
        AdminUi.loading(accountsTable, i18n);
        context.runAsync(() -> {
            List<AdminUser> all = new ArrayList<>();
            String cursor = null;
            do {
                AdminUserPage page = context.session().api().adminListUsers(
                        filter == null || filter.isBlank() ? null : filter,
                        null, "createdAt", "desc", cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            AdminUi.setItems(accountsTable, all);
            AdminUi.empty(accountsTable, i18n, "admin.accounts.emptyTitle", "admin.accounts.emptyHint");
            statusLabel.setText(i18n.format("admin.accounts.count", all.size()));
            AdminUser target = all.stream().filter(user -> user.getId().equals(currentId)).findFirst()
                    .orElse(all.isEmpty() ? null : all.getFirst());
            if (target != null) {
                accountsTable.getSelectionModel().select(target);
                if (!creating) showUser(target);
            } else if (!creating) {
                clearDetails();
            }
        }, exception -> {
            statusLabel.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(accountsTable, i18n, exception, () -> loadUsers(preferredId));
            if (!creating) clearDetails();
        });
    }

    private void showUser(AdminUser user) {
        selectedUser = user;
        creating = false;
        detailsTitle.setText(i18n.format("admin.accounts.detailsTitle", user.getUsername()));
        clearDetailsError();
        detailsStatus.setText(i18n.get("admin.accounts.detailsHint"));
        usernameField.setText(user.getUsername());
        usernameField.setDisable(true);
        fullNameField.setText(safe(user.getFullName()));
        emailField.setText(safe(user.getEmail()));
        addressField.setText(safe(user.getAddress()));
        birthDateField.setValue(user.getBirthDate());
        genderField.setValue(GenderOption.fromValue(user.getGender() == null
                ? null : user.getGender().getValue()));
        passwordBox.setVisible(false);
        passwordBox.setManaged(false);
        passwordField.clear();
        setFormEditable(true);
        setExistingActions(true);
        lockButton.setText(i18n.get(user.getStatus() == AdminUser.StatusEnum.LOCKED
                ? "admin.accounts.unlock" : "admin.accounts.lock"));
        cancelButton.setVisible(true);
        hideActionConfirmation();
        loadHistory(user.getId());
        loadFriends(user.getId());
    }

    private void beginCreate() {
        creating = true;
        selectedUser = null;
        accountsTable.getSelectionModel().clearSelection();
        detailsTitle.setText(i18n.get("admin.accounts.createTitle"));
        clearDetailsError();
        detailsStatus.setText(i18n.get("admin.accounts.createHint"));
        usernameField.clear();
        usernameField.setDisable(false);
        fullNameField.clear();
        emailField.clear();
        addressField.clear();
        birthDateField.setValue(null);
        genderField.setValue(null);
        passwordField.clear();
        passwordBox.setVisible(true);
        passwordBox.setManaged(true);
        setFormEditable(true);
        setExistingActions(false);
        cancelButton.setVisible(true);
        hideActionConfirmation();
        historyTable.getItems().clear();
        friendsTable.getItems().clear();
        historyStatus.setText(i18n.get("admin.accounts.createHint"));
        friendStatus.setText(i18n.get("admin.accounts.createHint"));
        usernameField.requestFocus();
    }

    private void clearDetails() {
        selectedUser = null;
        creating = false;
        detailsTitle.setText(i18n.get("admin.accounts.detailsHint"));
        detailsStatus.setText(i18n.get("admin.accounts.detailsHint"));
        clearDetailsError();
        usernameField.clear();
        fullNameField.clear();
        emailField.clear();
        addressField.clear();
        birthDateField.setValue(null);
        genderField.setValue(null);
        passwordField.clear();
        passwordBox.setVisible(false);
        passwordBox.setManaged(false);
        setFormEditable(false);
        setExistingActions(false);
        usernameField.setDisable(true);
        cancelButton.setVisible(false);
        hideActionConfirmation();
        historyTable.getItems().clear();
        friendsTable.getItems().clear();
        historyStatus.setText(i18n.get("admin.accounts.selectHint"));
        friendStatus.setText(i18n.get("admin.accounts.selectHint"));
    }

    private void setFormEditable(boolean editable) {
        fullNameField.setDisable(!editable);
        emailField.setDisable(!editable);
        addressField.setDisable(!editable);
        birthDateField.setDisable(!editable);
        genderField.setDisable(!editable);
        passwordField.setDisable(!editable);
        saveButton.setDisable(!editable);
    }

    private void setExistingActions(boolean enabled) {
        resetPasswordButton.setDisable(!enabled);
        lockButton.setDisable(!enabled);
        deleteButton.setDisable(!enabled);
        usernameField.setDisable(enabled);
    }

    private void saveUser() {
        String validation = validationError();
        if (validation != null) {
            showDetailsError(validation);
            return;
        }
        if (creating) createUser();
        else updateUser();
    }

    private void createUser() {
        Registration registration = new Registration()
                .username(usernameField.getText().strip())
                .fullName(fullNameField.getText().strip())
                .email(emailField.getText().strip())
                .address(addressField.getText().strip())
                .birthDate(birthDateField.getValue())
                .gender(Registration.GenderEnum.fromValue(genderField.getValue().value()))
                .password(passwordField.getText());
        passwordField.clear();
        setBusy(true);
        detailsStatus.setText(i18n.get("admin.accounts.creating"));
        context.runAsync(() -> context.session().api().adminCreateUser(registration),
                created -> {
                    setBusy(false);
                    creating = false;
                    statusLabel.setText(i18n.get("admin.accounts.created"));
                    clearDetailsError();
                    detailsStatus.setText(i18n.get("admin.accounts.created"));
                    loadUsers(created.getId());
                }, exception -> {
                    setBusy(false);
                    showDetailsError(AdminErrors.message(exception, i18n));
                });
    }

    private void updateUser() {
        if (selectedUser == null) return;
        UUID userId = selectedUser.getId();
        ProfileUpdate update = new ProfileUpdate()
                .fullName(fullNameField.getText().strip())
                .email(emailField.getText().strip())
                .address(addressField.getText().strip())
                .birthDate(birthDateField.getValue())
                .gender(ProfileUpdate.GenderEnum.fromValue(genderField.getValue().value()));
        setBusy(true);
        detailsStatus.setText(i18n.get("admin.accounts.saving"));
        context.runAsync(() -> {
            context.session().api().adminUpdateUser(userId, update);
            return null;
        }, ignored -> {
            setBusy(false);
            statusLabel.setText(i18n.get("admin.accounts.saved"));
            clearDetailsError();
            detailsStatus.setText(i18n.get("admin.accounts.saved"));
            loadUsers(userId);
        }, exception -> {
            setBusy(false);
            showDetailsError(AdminErrors.message(exception, i18n));
        });
    }

    private void requestPasswordReset() {
        if (selectedUser == null) return;
        showActionConfirmation(PendingAction.RESET_PASSWORD, selectedUser);
    }

    private void requestLockToggle(AdminUser user) {
        if (user == null) return;
        showActionConfirmation(user.getStatus() == AdminUser.StatusEnum.LOCKED
                ? PendingAction.UNLOCK : PendingAction.LOCK, user);
    }

    private void requestDelete() {
        if (selectedUser == null) return;
        showActionConfirmation(PendingAction.DELETE, selectedUser);
    }

    private void showActionConfirmation(PendingAction action, AdminUser user) {
        if (!actionConfirmBox.isVisible() && actionConfirmBox.getScene() != null) {
            confirmationFocus = actionConfirmBox.getScene().getFocusOwner();
        }
        pendingAction = action;
        pendingUser = user;
        confirmActionLabel.setText(i18n.format(action.confirmKey, user.getUsername()));
        confirmActionButton.setText(i18n.get(action.confirmButtonKey));
        confirmActionButton.getStyleClass().removeAll("danger-button", "secondary-button");
        confirmActionButton.getStyleClass().add(action == PendingAction.RESET_PASSWORD
                ? "secondary-button" : "danger-button");
        actionConfirmBox.setVisible(true);
        actionConfirmBox.setManaged(true);
        confirmActionButton.requestFocus();
    }

    private void hideActionConfirmation() {
        boolean wasVisible = actionConfirmBox.isVisible();
        actionConfirmBox.setVisible(false);
        actionConfirmBox.setManaged(false);
        pendingAction = null;
        pendingUser = null;
        confirmActionButton.setText(i18n.get("admin.accounts.confirmAction"));
        confirmActionButton.setDisable(false);
        if (wasVisible && confirmationFocus != null) confirmationFocus.requestFocus();
        confirmationFocus = null;
    }

    private void confirmPendingAction() {
        PendingAction action = pendingAction;
        AdminUser user = pendingUser;
        hideActionConfirmation();
        if (action == null || user == null) return;
        switch (action) {
            case DELETE -> performDelete(user);
            case LOCK -> performLock(user, true);
            case UNLOCK -> performLock(user, false);
            case RESET_PASSWORD -> performPasswordReset(user);
        }
    }

    private void performPasswordReset(AdminUser user) {
        UUID userId = user.getId();
        resetPasswordButton.setDisable(true);
        detailsStatus.setText(i18n.get("admin.accounts.resettingPassword"));
        context.runAsync(() -> {
            context.session().api().adminRequestPasswordReset(userId);
            return null;
        }, ignored -> {
            resetPasswordButton.setDisable(false);
            clearDetailsError();
            detailsStatus.setText(i18n.get("admin.accounts.passwordResetRequested"));
        }, exception -> {
            resetPasswordButton.setDisable(false);
            showDetailsError(AdminErrors.message(exception, i18n));
        });
    }

    private void performLock(AdminUser user, boolean lock) {
        statusLabel.setText(i18n.get(lock ? "admin.accounts.locking" : "admin.accounts.unlocking"));
        context.runAsync(() -> {
            context.session().api().adminSetUserLock(user.getId(), new AdminSetUserLockRequest()
                    .locked(lock)
                    .reason(i18n.get(lock ? "admin.accounts.lockReason" : "admin.accounts.unlockReason")));
            return null;
        }, ignored -> {
            statusLabel.setText(i18n.get(lock ? "admin.accounts.locked" : "admin.accounts.unlocked"));
            loadUsers(user.getId());
        }, exception -> statusLabel.setText(AdminErrors.message(exception, i18n)));
    }

    private void performDelete(AdminUser user) {
        UUID userId = user.getId();
        detailsStatus.setText(i18n.get("admin.accounts.deleting"));
        context.runAsync(() -> {
            context.session().api().adminDeleteUser(userId);
            return null;
        }, ignored -> {
            selectedUser = null;
            statusLabel.setText(i18n.get("admin.accounts.deleted"));
            clearDetails();
            loadUsers(null);
        }, exception -> showDetailsError(AdminErrors.message(exception, i18n)));
    }

    private void loadHistory(UUID userId) {
        historyStatus.setText(i18n.get("admin.accounts.loginHistoryLoading"));
        AdminUi.loading(historyTable, i18n);
        context.runAsync(() -> {
            List<LoginHistoryEntry> all = new ArrayList<>();
            String cursor = null;
            do {
                LoginHistoryPage page = context.session().api().adminGetUserLoginHistory(userId, cursor, 100);
                if (page == null || page.getItems() == null) break;
                all.addAll(page.getItems());
                cursor = page.getNextCursor();
            } while (cursor != null && !cursor.isBlank());
            return all;
        }, all -> {
            if (!isSelected(userId)) return;
            AdminUi.setItems(historyTable, all);
            AdminUi.empty(historyTable, i18n, "admin.accounts.loginHistoryEmptyTitle",
                    "admin.accounts.loginHistoryEmptyHint");
            historyStatus.setText(i18n.format("admin.accounts.loginHistoryCount", all.size()));
        }, exception -> {
            if (!isSelected(userId)) return;
            historyStatus.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(historyTable, i18n, exception, () -> loadHistory(userId));
        });
    }

    private void loadFriends(UUID userId) {
        friendStatus.setText(i18n.get("admin.accounts.friendListLoading"));
        AdminUi.loading(friendsTable, i18n);
        context.runAsync(() -> {
            UserPage page = context.session().api().adminListUserFriends(userId);
            return page == null || page.getItems() == null ? List.<User>of() : page.getItems();
        }, all -> {
            if (!isSelected(userId)) return;
            AdminUi.setItems(friendsTable, all);
            AdminUi.empty(friendsTable, i18n, "admin.accounts.friendListEmptyTitle",
                    "admin.accounts.friendListEmptyHint");
            friendStatus.setText(i18n.format("admin.accounts.friendListCount", all.size()));
        }, exception -> {
            if (!isSelected(userId)) return;
            friendStatus.setText(AdminErrors.message(exception, i18n));
            AdminUi.error(friendsTable, i18n, exception, () -> loadFriends(userId));
        });
    }

    private boolean isSelected(UUID userId) {
        return selectedUser != null && selectedUser.getId().equals(userId);
    }

    private void cancelEdit() {
        hideActionConfirmation();
        if (creating) {
            creating = false;
            loadUsers(null);
        } else if (selectedUser != null) {
            showUser(selectedUser);
        } else {
            loadUsers(null);
        }
    }

    private String validationError() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().strip();
        String fullName = fullNameField.getText() == null ? "" : fullNameField.getText().strip();
        String email = emailField.getText() == null ? "" : emailField.getText().strip();
        String address = addressField.getText() == null ? "" : addressField.getText().strip();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (creating && !username.matches("[A-Za-z0-9_.-]{3,32}")) {
            return i18n.get("admin.accounts.usernameInvalid");
        }
        if (fullName.isBlank() || email.isBlank() || address.isBlank()) {
            return i18n.get("admin.accounts.required");
        }
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            return i18n.get("admin.accounts.emailInvalid");
        }
        if (birthDateField.getValue() == null || !birthDateField.getValue().isBefore(LocalDate.now())) {
            return i18n.get("admin.accounts.birthDateInvalid");
        }
        if (genderField.getValue() == null) {
            return i18n.get("admin.accounts.genderRequired");
        }
        if (creating && password.length() < 12) {
            return i18n.get("admin.accounts.passwordTooShort");
        }
        return null;
    }

    private void setBusy(boolean busy) {
        saveButton.setDisable(busy);
        newUserButton.setDisable(busy);
    }

    private void showDetailsError(String message) {
        detailsStatus.setText(message);
        if (!detailsStatus.getStyleClass().contains("error")) {
            detailsStatus.getStyleClass().add("error");
        }
    }

    private void clearDetailsError() {
        detailsStatus.getStyleClass().remove("error");
    }

    private String statusText(AdminUser user) {
        return i18n.get(user.getStatus() == AdminUser.StatusEnum.LOCKED
                ? "admin.accounts.statusLocked" : "admin.accounts.statusActive");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private enum PendingAction {
        DELETE("admin.accounts.deleteConfirm", "admin.accounts.deleteConfirmAction"),
        LOCK("admin.accounts.lockConfirm", "admin.accounts.lock"),
        UNLOCK("admin.accounts.unlockConfirm", "admin.accounts.unlock"),
        RESET_PASSWORD("admin.accounts.resetConfirm", "admin.accounts.resetPassword");

        private final String confirmKey;
        private final String confirmButtonKey;

        PendingAction(String confirmKey, String confirmButtonKey) {
            this.confirmKey = confirmKey;
            this.confirmButtonKey = confirmButtonKey;
        }
    }

    enum GenderOption {
        FEMALE("female", "admin.gender.female"),
        MALE("male", "admin.gender.male"),
        OTHER("other", "admin.gender.other"),
        UNDISCLOSED("undisclosed", "admin.gender.undisclosed");

        private final String value;
        private final String key;

        GenderOption(String value, String key) {
            this.value = value;
            this.key = key;
        }

        String value() {
            return value;
        }

        String key() {
            return key;
        }

        static GenderOption fromValue(String value) {
            for (GenderOption option : values()) {
                if (option.value.equals(value)) return option;
            }
            return null;
        }
    }
}
