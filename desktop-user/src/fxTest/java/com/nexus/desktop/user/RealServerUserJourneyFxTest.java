package com.nexus.desktop.user;

import com.nexus.desktop.shared.SessionApi;
import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.model.AdminUser;
import com.nexus.sdk.model.ChangePasswordRequest;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.CreateDirectConversationRequest;
import com.nexus.sdk.model.CreateGroup;
import com.nexus.sdk.model.DecideFriendRequestRequest;
import com.nexus.sdk.model.Login;
import com.nexus.sdk.model.Message;
import com.nexus.sdk.model.MessageSubmission;
import com.nexus.sdk.model.ProfileUpdate;
import com.nexus.sdk.model.Registration;
import com.nexus.sdk.model.Report;
import com.nexus.sdk.model.SendFriendRequestRequest;
import com.nexus.sdk.model.SetGroupMemberRoleRequest;
import com.nexus.sdk.model.User;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in end-to-end user journey against the local PostgreSQL + Redis + server stack. It creates its
 * own accounts, drives the FXML account and group screens plus the report dialog, verifies the
 * server state, and deletes every created account through the admin API afterwards.
 *
 * <pre>
 * NEXUS_REAL_SERVER=true NEXUS_REAL_ADMIN_USERNAME=admin NEXUS_REAL_ADMIN_PASSWORD=... \
 *   DISPLAY=:0 ./gradlew :desktop-user:fxTest --tests '*RealServerUserJourneyFxTest*'
 * </pre>
 *
 * The server must accept HTTP cookies during the run, for example
 * {@code NEXUS_SECURE_COOKIE=false docker compose up -d server}; restore the default afterwards.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_REAL_SERVER", matches = "true")
class RealServerUserJourneyFxTest {
    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "a different horse battery staple";

    @Test
    void drivesRegistrationFriendshipReportGroupsAndPasswordChange(@TempDir Path home) throws Exception {
        String origin = env("NEXUS_REAL_SERVER_ORIGIN", "http://127.0.0.1:8080");
        String adminUser = System.getenv("NEXUS_REAL_ADMIN_USERNAME");
        String adminPassword = System.getenv("NEXUS_REAL_ADMIN_PASSWORD");
        assertThat(adminUser).as("NEXUS_REAL_ADMIN_USERNAME").isNotBlank();
        assertThat(adminPassword).as("NEXUS_REAL_ADMIN_PASSWORD").isNotBlank();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String aliceName = "live_alice_" + suffix;
        String bobName = "live_bob_" + suffix;
        String reportReason = "Harassment " + suffix;
        String evidenceText = "Reported message " + suffix;
        String groupName = "Live group " + suffix;
        String renamedGroup = "Renamed group " + suffix;

        SessionApi admin = login(origin, adminUser, adminPassword);
        SessionApi alice = null;
        SessionApi bob = null;
        AdminUser aliceAdmin = null;
        AdminUser bobAdmin = null;
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            alice = registerAndLogin(origin, aliceName, "Live Alice");
            assertDuplicateRegistrationRejected(origin, aliceName);
            assertWrongCurrentPasswordRejected(alice);
            bob = registerAndLogin(origin, bobName, "Live Bob");
            final SessionApi aliceApi = alice;
            final SessionApi bobApi = bob;
            UUID aliceId = alice.api().getCurrentUser().getId();
            UUID bobId = bob.api().getCurrentUser().getId();
            aliceAdmin = admin.api().adminGetUser(aliceId);
            bobAdmin = admin.api().adminGetUser(bobId);

            Stage stage = onFx(Stage::new);
            ThemeManager theme = new ThemeManager();
            theme.setTheme(Theme.LIGHT);
            I18n i18n = new I18n(BASE, java.util.Locale.ENGLISH);
            UserAppContext context = new UserAppContext(stage, origin, false, null, theme, i18n);
            context.activate(context.signIn(aliceName, PASSWORD.toCharArray()));
            assertThat(call(() -> context.session().api().getCurrentUser().getUsername()))
                    .as("context session after sign-in").isEqualTo(aliceName);
            try {
                Parent firstAccount = openAccount(stage, context, theme, i18n);
                waitForFx(() -> listSize(firstAccount.getScene(), "#friendsList") == 0
                        && listSize(firstAccount.getScene(), "#incomingList") == 0, Duration.ofSeconds(20));

                search(firstAccount.getScene(), bobName);
                waitForFx(() -> listSize(firstAccount.getScene(), "#discoveryList") == 1, Duration.ofSeconds(20));
                onFx(() -> {
                    ((ListView<?>) firstAccount.getScene().lookup("#discoveryList"))
                            .getSelectionModel().selectFirst();
                    button(firstAccount.getScene(), "Send friend request").fire();
                    return true;
                });
                waitFor(() -> call(() -> bobApi.api().listFriendRequests(null, 20).getItems().stream()
                        .anyMatch(request -> request.getFrom().getId().equals(aliceId))),
                        Duration.ofSeconds(20));
                var pending = bobApi.api().listFriendRequests(null, 20).getItems().stream()
                        .filter(request -> request.getFrom().getId().equals(aliceId)).findFirst().orElseThrow();
                bobApi.api().decideFriendRequest(pending.getId(),
                        new DecideFriendRequestRequest().decision(
                                DecideFriendRequestRequest.DecisionEnum.ACCEPT));
                Conversation direct = bobApi.api().createDirectConversation(
                        new CreateDirectConversationRequest().participantId(aliceId));
                Message evidenceMessage = bobApi.api().sendMessage(direct.getId(),
                        UUID.randomUUID().toString(), new MessageSubmission().content(evidenceText));

                Parent fresh = openAccount(stage, context, theme, i18n);
                waitFor(() -> call(() -> aliceApi.api().listFriends(null, 20).getItems().size() == 1),
                        Duration.ofSeconds(20));
                waitForFx(() -> listSize(fresh.getScene(), "#friendsList") == 1, Duration.ofSeconds(20));

                begin(() -> {
                    ((ListView<?>) fresh.getScene().lookup("#friendsList")).getSelectionModel().selectFirst();
                    button(fresh.getScene(), "Block").fire();
                });
                waitForFx(RealServerUserJourneyFxTest::confirmVisible, Duration.ofSeconds(10));
                clickConfirm(false);
                assertThat(aliceApi.api().listBlockedUsers(null, 20).getItems()).isEmpty();
                begin(() -> button(fresh.getScene(), "Block").fire());
                waitForFx(RealServerUserJourneyFxTest::confirmVisible, Duration.ofSeconds(10));
                clickConfirm(true);
                waitForFx(() -> listSize(fresh.getScene(), "#blockedList") == 1, Duration.ofSeconds(20));
                assertBlockedFriendRequestRejected(aliceApi, bobId);

                onFx(() -> {
                    ((ListView<?>) fresh.getScene().lookup("#blockedList")).getSelectionModel().selectFirst();
                    button(fresh.getScene(), "Unblock").fire();
                    return true;
                });
                waitFor(() -> call(() -> aliceApi.api().listBlockedUsers(null, 20).getItems().isEmpty()),
                        Duration.ofSeconds(20));

                aliceApi.api().sendFriendRequest(new SendFriendRequestRequest().userId(bobId));
                var second = bobApi.api().listFriendRequests(null, 20).getItems().stream()
                        .filter(request -> request.getFrom().getId().equals(aliceId)).findFirst().orElseThrow();
                bobApi.api().decideFriendRequest(second.getId(),
                        new DecideFriendRequestRequest().decision(
                                DecideFriendRequestRequest.DecisionEnum.ACCEPT));
                Parent friendScreen = openAccount(stage, context, theme, i18n);
                waitForFx(() -> listSize(friendScreen.getScene(), "#friendsList") == 1, Duration.ofSeconds(20));

                begin(() -> {
                    ((ListView<?>) friendScreen.getScene().lookup("#friendsList"))
                            .getSelectionModel().selectFirst();
                    button(friendScreen.getScene(), "Unfriend").fire();
                });
                waitForFx(RealServerUserJourneyFxTest::confirmVisible, Duration.ofSeconds(10));
                clickConfirm(false);
                assertThat(aliceApi.api().listFriends(null, 20).getItems()).hasSize(1);
                begin(() -> button(friendScreen.getScene(), "Unfriend").fire());
                waitForFx(RealServerUserJourneyFxTest::confirmVisible, Duration.ofSeconds(10));
                clickConfirm(true);
                waitFor(() -> call(() -> aliceApi.api().listFriends(null, 20).getItems().isEmpty()),
                        Duration.ofSeconds(20));
                aliceApi.api().sendFriendRequest(new SendFriendRequestRequest().userId(bobId));
                var third = bobApi.api().listFriendRequests(null, 20).getItems().stream()
                        .filter(request -> request.getFrom().getId().equals(aliceId)).findFirst().orElseThrow();
                bobApi.api().decideFriendRequest(third.getId(),
                        new DecideFriendRequestRequest().decision(
                                DecideFriendRequestRequest.DecisionEnum.ACCEPT));
                waitFor(() -> call(() -> aliceApi.api().listFriends(null, 20).getItems().size() == 1),
                        Duration.ofSeconds(20));

                String updatedName = "Live Alice Updated " + suffix;
                onFx(() -> {
                    field(friendScreen.getScene(), "Full name").setText(updatedName);
                    button(friendScreen.getScene(), "Save profile").fire();
                    return true;
                });
                waitFor(() -> call(() -> updatedName.equals(aliceApi.api().getCurrentUser().getFullName())),
                        Duration.ofSeconds(20));
                assertThat(aliceApi.api().getCurrentUser().getFullName()).isEqualTo(updatedName);

                onFx(() -> {
                    ((PasswordField) friendScreen.getScene().lookup("#currentPasswordField"))
                            .setText(PASSWORD);
                    ((PasswordField) friendScreen.getScene().lookup("#newPasswordField"))
                            .setText(NEW_PASSWORD);
                    ((PasswordField) friendScreen.getScene().lookup("#confirmPasswordField"))
                            .setText(NEW_PASSWORD);
                    button(friendScreen.getScene(), "Change password").fire();
                    return true;
                });
                waitFor(() -> labelContaining(friendScreen.getScene(), "Password changed")
                        || labelContaining(friendScreen.getScene(), "password changed"), Duration.ofSeconds(20));
                SessionApi changed = login(origin, aliceName, NEW_PASSWORD);
                changed.clear();

                User bobUser = new User().id(bobId).username(bobName).fullName("Live Bob");
                CachedMessage evidence = new CachedMessage(evidenceMessage.getId(), direct.getId(),
                        bobId.toString(), evidenceText, evidenceMessage.getCreatedAt().toInstant());
                onFx(() -> {
                    ReportController controller = new ReportController(context, bobUser,
                            List.of(evidence), () -> { });
                    Parent report = ViewLoader.load(ViewLoader.REPORT, controller, i18n);
                    Scene scene = new Scene(report, 560, 620);
                    theme.apply(scene);
                    stage.setScene(scene);
                    stage.show();
                    return true;
                });
                Scene reportScene = stage.getScene();
                onFx(() -> {
                    ((TextArea) reportScene.lookup("#reasonField")).setText(reportReason);
                    ((ListView<?>) reportScene.lookup("#evidenceList")).getSelectionModel().selectFirst();
                    ((javafx.scene.control.CheckBox) reportScene.lookup("#consentCheck")).setSelected(true);
                    button(reportScene, "Submit report").fire();
                    return true;
                });
                waitFor(() -> reportByReason(admin, bobName, reportReason) != null, Duration.ofSeconds(20));
                Report report = reportByReason(admin, bobName, reportReason);
                assertThat(report.getStatus()).isEqualTo(Report.StatusEnum.PENDING);
                assertThat(report.getSelectedEvidence()).isNotEmpty();

                Conversation group = context.session().api().createGroup(new CreateGroup().name(groupName)
                        .addInvitedUserIdsItem(bobId));
                onFx(() -> {
                    GroupSettingsController controller = new GroupSettingsController(context, group,
                            () -> { }, () -> { }, () -> { });
                    Parent settings = ViewLoader.load(ViewLoader.GROUP_SETTINGS, controller, i18n);
                    Scene scene = new Scene(settings, 640, 600);
                    theme.apply(scene);
                    stage.setScene(scene);
                    stage.show();
                    return true;
                });
                Scene groupScene = stage.getScene();
                waitForFx(() -> listSize(groupScene, "#membersList") == 2, Duration.ofSeconds(20));
                onFx(() -> {
                    field(groupScene, "Group name").setText(renamedGroup);
                    button(groupScene, "Rename").fire();
                    return true;
                });
                waitFor(() -> call(() -> renamedGroup.equals(
                        context.session().api().listGroups(null, 100).getItems().stream()
                                .filter(item -> group.getId().equals(item.getId()))
                                .map(Conversation::getName).findFirst().orElse(null))), Duration.ofSeconds(20));
                onFx(() -> {
                    ListView<?> members = (ListView<?>) groupScene.lookup("#membersList");
                    for (int index = 0; index < members.getItems().size(); index++) {
                        var member = (com.nexus.sdk.model.GroupMember)
                                members.getItems().get(index);
                        if (bobId.equals(member.getUser().getId())) {
                            members.getSelectionModel().select(index);
                            break;
                        }
                    }
                    button(groupScene, "Promote").fire();
                    return true;
                });
                waitFor(() -> memberRole(admin, group.getId(), bobId)
                        == SetGroupMemberRoleRequest.RoleEnum.ADMIN, Duration.ofSeconds(20));

                begin(() -> button(groupScene, "Leave group").fire());
                waitForFx(RealServerUserJourneyFxTest::confirmVisible, Duration.ofSeconds(10));
                clickConfirm(false);
                assertThat(groupMembers(admin, group.getId())).hasSize(2);
                begin(() -> button(groupScene, "Leave group").fire());
                waitForFx(RealServerUserJourneyFxTest::confirmVisible, Duration.ofSeconds(10));
                clickConfirm(true);
                waitFor(() -> groupMembers(admin, group.getId()).size() == 1, Duration.ofSeconds(20));
                assertThat(groupMembers(admin, group.getId()).getFirst().getUser().getId()).isEqualTo(bobId);
            } finally {
                onFx(() -> {
                    if (stage != null) stage.close();
                    context.close();
                    return true;
                });
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            cleanup(admin, aliceAdmin, bobAdmin);
            if (alice != null) alice.clear();
            if (bob != null) bob.clear();
            admin.clear();
        }
    }

    private static Parent openAccount(Stage stage, UserAppContext context, ThemeManager theme, I18n i18n) {
        return onFx(() -> {
            AccountController controller = new AccountController(context, id -> { }, user -> { },
                    () -> { }, () -> { });
            Parent root = ViewLoader.load(ViewLoader.ACCOUNT, controller, i18n);
            Scene scene = new Scene(root, 960, 720);
            theme.apply(scene);
            stage.setScene(scene);
            stage.show();
            return root;
        });
    }

    private static void search(Scene scene, String query) {
        onFx(() -> {
            field(scene, "Search by username or name").setText(query);
            button(scene, "Search").fire();
            return true;
        });
    }

    private static Report reportByReason(SessionApi admin, String reportedUsername, String reason) {
        try {
            var page = admin.api().adminListReports(reportedUsername, null, null,
                    "createdAt", "desc", null, 50);
            if (page == null || page.getItems() == null) return null;
            return page.getItems().stream().filter(report -> reason.equals(report.getReason()))
                    .findFirst().orElse(null);
        } catch (Exception exception) {
            throw new AssertionError("Could not read reports from the real server.", exception);
        }
    }

    private static List<com.nexus.sdk.model.GroupMember> groupMembers(
            SessionApi admin, UUID groupId) {
        try {
            var page = admin.api().adminListGroupMembers(groupId);
            return page == null || page.getItems() == null ? List.of() : page.getItems();
        } catch (Exception exception) {
            throw new AssertionError("Could not read group members from the real server.", exception);
        }
    }

    private static SetGroupMemberRoleRequest.RoleEnum memberRole(SessionApi admin, UUID groupId,
                                                                 UUID userId) {
        return groupMembers(admin, groupId).stream()
                .filter(member -> userId.equals(member.getUser().getId()))
                .map(com.nexus.sdk.model.GroupMember::getRole)
                .map(role -> SetGroupMemberRoleRequest.RoleEnum.fromValue(role.getValue()))
                .findFirst().orElse(null);
    }

    private static SessionApi registerAndLogin(String origin, String username, String fullName)
            throws Exception {
        SessionApi publicApi = new SessionApi(URI.create(origin));
        try {
            publicApi.api().getCsrfToken();
            publicApi.api().register(new Registration()
                    .username(username)
                    .fullName(fullName)
                    .email(username + "@example.test")
                    .address("Live test address")
                    .birthDate(LocalDate.of(1991, 2, 3))
                    .gender(Registration.GenderEnum.OTHER)
                    .password(PASSWORD));
        } finally {
            publicApi.clear();
        }
        return login(origin, username, PASSWORD);
    }

    private static void assertDuplicateRegistrationRejected(String origin, String username)
            throws Exception {
        SessionApi publicApi = new SessionApi(URI.create(origin));
        try {
            publicApi.api().getCsrfToken();
            assertThatThrownBy(() -> publicApi.api().register(new Registration()
                    .username(username)
                    .fullName("Duplicate Live User")
                    .email(username + "@example.test")
                    .address("Duplicate address")
                    .birthDate(LocalDate.of(1992, 4, 5))
                    .gender(Registration.GenderEnum.OTHER)
                    .password(PASSWORD)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(exception -> assertThat(((ApiException) exception).getCode())
                            .isEqualTo(409));
        } finally {
            publicApi.clear();
        }
    }

    private static void assertWrongCurrentPasswordRejected(SessionApi session) {
        assertThatThrownBy(() -> session.api().changePassword(new ChangePasswordRequest()
                .currentPassword("definitely the wrong current password")
                .newPassword(NEW_PASSWORD)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getCode())
                        .isEqualTo(401));
    }

    private static void assertBlockedFriendRequestRejected(SessionApi session, UUID targetId) {
        assertThatThrownBy(() -> session.api().sendFriendRequest(
                new SendFriendRequestRequest().userId(targetId)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getCode())
                        .isEqualTo(409));
    }

    private static SessionApi login(String origin, String username, String password) throws Exception {
        SessionApi session = new SessionApi(URI.create(origin));
        session.api().getCsrfToken();
        session.api().login(new Login().username(username).password(password));
        return session;
    }

    private static void cleanup(SessionApi admin, AdminUser... users) {
        for (AdminUser user : users) {
            if (user == null) continue;
            try {
                admin.api().adminDeleteUser(user.getId());
            } catch (Exception ignored) {
                // Best-effort cleanup; the test still reports the journey result.
            }
        }
    }

    private static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (Exception exception) {
            throw new AssertionError("Real server call failed.", exception);
        }
    }

    private static int listSize(Scene scene, String id) {
        return scene.lookup(id) instanceof ListView<?> list ? list.getItems().size() : 0;
    }

    private static boolean labelContaining(Scene scene, String text) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()) && candidate.isVisible())
                .findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(candidate -> prompt.equals(candidate.getPromptText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing field: " + prompt));
    }

    private static boolean confirmVisible() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().lookup("#confirmDialogConfirm") != null) return true;
        }
        return false;
    }

    private static void clickConfirm(boolean confirm) {
        onFx(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null) {
                    Button button = (Button) window.getScene().lookup(confirm
                            ? "#confirmDialogConfirm" : "#confirmDialogCancel");
                    if (button != null) {
                        button.fire();
                        return true;
                    }
                }
            }
            throw new AssertionError("Confirmation dialog is not visible.");
        });
    }

    private static void begin(Runnable action) {
        Platform.runLater(action);
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep();
        }
        throw new AssertionError("Timed out waiting for the real server.");
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The account screen can still be loading its lists.
            }
            sleep();
        }
        throw new AssertionError("Timed out waiting for the JavaFX screen. " + fxState());
    }

    private static String fxState() {
        try {
            return onFx(() -> {
                for (Window window : Window.getWindows()) {
                    if (!window.isShowing() || window.getScene() == null) continue;
                    Scene scene = window.getScene();
                    if (scene.lookup("#friendsList") == null) continue;
                    List<String> labels = scene.getRoot().lookupAll(".label").stream()
                            .filter(Label.class::isInstance).map(Label.class::cast)
                            .map(Label::getText).filter(text -> text != null && !text.isBlank())
                            .toList();
                    return "friends=" + listSize(scene, "#friendsList")
                            + " blocked=" + listSize(scene, "#blockedList")
                            + " labels=" + labels;
                }
                return "no account scene";
            });
        } catch (RuntimeException exception) {
            return "state unavailable: " + exception.getMessage();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting.", exception);
        }
    }

    private static <T> T onFx(Callable<T> action) {
        startToolkit();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("JavaFX action failed", exception);
        }
    }

    private static synchronized void startToolkit() {
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX.", exception);
        }
        Platform.setImplicitExit(false);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
