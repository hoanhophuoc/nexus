package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.SessionApi;
import com.haichutieu.chatsystem.sdk.model.AdminUser;
import com.haichutieu.chatsystem.sdk.model.Conversation;
import com.haichutieu.chatsystem.sdk.model.CreateDirectConversationRequest;
import com.haichutieu.chatsystem.sdk.model.DecideFriendRequestRequest;
import com.haichutieu.chatsystem.sdk.model.Login;
import com.haichutieu.chatsystem.sdk.model.Message;
import com.haichutieu.chatsystem.sdk.model.MessageSubmission;
import com.haichutieu.chatsystem.sdk.model.Registration;
import com.haichutieu.chatsystem.sdk.model.Report;
import com.haichutieu.chatsystem.sdk.model.ReportSubmission;
import com.haichutieu.chatsystem.sdk.model.ReportSubmissionSelectedEvidenceInner;
import com.haichutieu.chatsystem.sdk.model.SendFriendRequestRequest;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in admin smoke test against a real server. It creates its own users, a friendship,
 * a reported message with evidence and then drives the FXML admin shell end to end.
 *
 * <pre>
 * CHAT_REAL_SERVER=true CHAT_REAL_ADMIN_USERNAME=admin CHAT_REAL_ADMIN_PASSWORD=... \
 *   ./gradlew :desktop-admin:fxTest --tests '*RealServerAdminFlowFxTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHAT_REAL_SERVER", matches = "true")
class RealServerAdminFlowFxTest {
    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void drivesEveryAdminWorkspaceAgainstTheRealServer() throws Exception {
        String origin = env("CHAT_REAL_SERVER_ORIGIN", "http://127.0.0.1:8080");
        String adminUsername = System.getenv("CHAT_REAL_ADMIN_USERNAME");
        String adminPassword = System.getenv("CHAT_REAL_ADMIN_PASSWORD");
        assertThat(adminUsername).as("CHAT_REAL_ADMIN_USERNAME").isNotBlank();
        assertThat(adminPassword).as("CHAT_REAL_ADMIN_PASSWORD").isNotBlank();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String targetUsername = "live_target_" + suffix;
        String reporterUsername = "live_reporter_" + suffix;
        String createdUsername = "live_created_" + suffix;
        String reason = "Harassment evidence " + suffix;
        String evidence = "I will find you after class. " + suffix;

        SessionApi adminApi = login(origin, adminUsername, adminPassword);
        SessionApi reporterApi = null;
        SessionApi targetApi = null;
        AdminUser target = null;
        AdminUser reporter = null;
        try {
            target = adminApi.api().adminCreateUser(registration(targetUsername, "Live Target",
                    targetUsername + "@example.test", "2 Target Road"));
            reporter = adminApi.api().adminCreateUser(registration(reporterUsername, "Live Reporter",
                    reporterUsername + "@example.test", "3 Reporter Road"));

            targetApi = login(origin, targetUsername, PASSWORD);
            reporterApi = login(origin, reporterUsername, PASSWORD);
            reporterApi.api().sendFriendRequest(new SendFriendRequestRequest().userId(target.getId()));
            var requests = targetApi.api().listFriendRequests(null, 10);
            assertThat(requests.getItems()).as("pending friend requests").isNotEmpty();
            targetApi.api().decideFriendRequest(requests.getItems().getFirst().getId(),
                    new DecideFriendRequestRequest().decision(DecideFriendRequestRequest.DecisionEnum.ACCEPT));

            Conversation direct = reporterApi.api().createDirectConversation(
                    new CreateDirectConversationRequest().participantId(target.getId()));
            Message message = reporterApi.api().sendMessage(direct.getId(), UUID.randomUUID().toString(),
                    new MessageSubmission().content(evidence));
            reporterApi.api().reportUser(new ReportSubmission()
                    .reportedUserId(target.getId())
                    .reason(reason)
                    .evidenceConsent(true)
                    .addSelectedEvidenceItem(new ReportSubmissionSelectedEvidenceInner()
                            .messageId(message.getId()).submittedContent(evidence)));
            assertThat(pendingReport(adminApi, targetUsername, reason)).isNotNull();

            UUID targetId = target.getId();
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<AdminDesktopApplication> applicationReference = new AtomicReference<>();
            onFx(() -> {
                AdminDesktopApplication application = new AdminDesktopApplication(origin);
                applicationReference.set(application);
                Stage stage = new Stage();
                stageReference.set(stage);
                application.start(stage);
                return true;
            });
            Stage stage = stageReference.get();
            AdminDesktopApplication application = applicationReference.get();
            try {
                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Administrator username").setText(adminUsername);
                    field(scene, "Password").setText(adminPassword);
                    button(scene, "Sign in").fire();
                    return true;
                });
                waitForFx(() -> stage.getScene() != null && stage.getScene().lookup(".admin-nav") != null,
                        Duration.ofSeconds(20));
                waitForFx(() -> rowIndex(stage, 0, targetUsername) >= 0, Duration.ofSeconds(20));

                selectRow(stage, 0, targetUsername);
                waitForFx(() -> tableRows(stage, 1) > 0 && rowIndex(stage, 2, reporterUsername) >= 0,
                        Duration.ofSeconds(20));

                String updatedName = "Live Target Updated " + suffix;
                onFx(() -> {
                    field(stage.getScene(), "Full name").setText(updatedName);
                    button(stage.getScene(), "Save").fire();
                    return true;
                });
                waitFor(() -> updatedName.equals(userById(adminApi, targetId).getFullName()),
                        Duration.ofSeconds(20));

                onFx(() -> {
                    buttonById(stage.getScene(), "#lockButton").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelActionButton").fire();
                    return true;
                });
                assertThat(userById(adminApi, targetId).getStatus()).isEqualTo(AdminUser.StatusEnum.ACTIVE);
                onFx(() -> {
                    buttonById(stage.getScene(), "#lockButton").fire();
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitFor(() -> userById(adminApi, targetId).getStatus() == AdminUser.StatusEnum.LOCKED,
                        Duration.ofSeconds(20));

                onFx(() -> {
                    buttonById(stage.getScene(), "#lockButton").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitFor(() -> userById(adminApi, targetId).getStatus() == AdminUser.StatusEnum.ACTIVE,
                        Duration.ofSeconds(20));

                onFx(() -> {
                    buttonById(stage.getScene(), "#resetPasswordButton").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelActionButton").fire();
                    return true;
                });
                onFx(() -> {
                    buttonById(stage.getScene(), "#resetPasswordButton").fire();
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitForFx(() -> labelContaining(stage, "Password reset requested."), Duration.ofSeconds(20));

                onFx(() -> {
                    button(stage.getScene(), "New user").fire();
                    field(stage.getScene(), "Username").setText(createdUsername);
                    field(stage.getScene(), "Full name").setText("Live Created");
                    field(stage.getScene(), "Email").setText(createdUsername + "@example.test");
                    field(stage.getScene(), "Address").setText("4 Created Road");
                    ((DatePicker) stage.getScene().lookup("#birthDateField")).setValue(LocalDate.of(1992, 4, 6));
                    ((ComboBox<?>) stage.getScene().lookup("#genderField")).getSelectionModel().select(1);
                    field(stage.getScene(), "At least 12 characters").setText(PASSWORD);
                    button(stage.getScene(), "Save").fire();
                    return true;
                });
                waitFor(() -> userByUsername(adminApi, createdUsername) != null, Duration.ofSeconds(20));
                waitForFx(() -> rowIndex(stage, 0, createdUsername) >= 0, Duration.ofSeconds(20));

                selectRow(stage, 0, createdUsername);
                onFx(() -> {
                    buttonById(stage.getScene(), "#deleteButton").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelActionButton").fire();
                    return true;
                });
                assertThat(userByUsername(adminApi, createdUsername)).as("cancel keeps the account").isNotNull();
                onFx(() -> {
                    buttonById(stage.getScene(), "#deleteButton").fire();
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitFor(() -> userByUsername(adminApi, createdUsername) == null, Duration.ofSeconds(20));
                waitForFx(() -> rowIndex(stage, 0, createdUsername) < 0, Duration.ofSeconds(20));

                selectScreen(stage, AdminScreen.REPORTS);
                onFx(() -> {
                    field(stage.getScene(), "Filter by reported username").setText(targetUsername);
                    button(stage.getScene(), "Load reports").fire();
                    return true;
                });
                waitForFx(() -> rowIndex(stage, 0, targetUsername) >= 0, Duration.ofSeconds(20));
                onFx(() -> {
                    button(stage.getScene(), "View evidence").fire();
                    return true;
                });
                waitForFx(() -> tableContains(stage, 1, evidence), Duration.ofSeconds(20));
                onFx(() -> {
                    button(stage.getScene(), "Uphold").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#decisionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelDecisionButton").fire();
                    return true;
                });
                assertThat(pendingReport(adminApi, targetUsername, reason)).as("cancel leaves report pending").isNotNull();
                onFx(() -> {
                    button(stage.getScene(), "Uphold").fire();
                    buttonById(stage.getScene(), "#confirmDecisionButton").fire();
                    return true;
                });
                waitFor(() -> {
                    Report report = reportByReason(adminApi, targetUsername, reason);
                    return report != null && report.getStatus() == Report.StatusEnum.UPHELD;
                }, Duration.ofSeconds(20));

                onFx(() -> {
                    button(stage.getScene(), "Sign out").fire();
                    return true;
                });
                waitForFx(() -> stage.getScene() != null && fieldOrNull(stage.getScene(), "Administrator username") != null,
                        Duration.ofSeconds(20));
            } finally {
                onFx(() -> {
                    stage.close();
                    application.stop();
                    return true;
                });
            }
        } finally {
            cleanup(adminApi, target, reporter);
            if (targetApi != null) targetApi.clear();
            if (reporterApi != null) reporterApi.clear();
            adminApi.clear();
        }
    }

    private static Registration registration(String username, String fullName, String email, String address) {
        return new Registration()
                .username(username)
                .fullName(fullName)
                .email(email)
                .address(address)
                .birthDate(LocalDate.of(1990, 1, 1))
                .gender(Registration.GenderEnum.OTHER)
                .password(PASSWORD);
    }

    private static SessionApi login(String origin, String username, String password) throws Exception {
        SessionApi session = new SessionApi(URI.create(origin));
        session.api().getCsrfToken();
        session.api().login(new Login().username(username).password(password));
        return session;
    }

    private static Report pendingReport(SessionApi admin, String reportedUsername, String reason) {
        Report report = reportByReason(admin, reportedUsername, reason);
        return report != null && report.getStatus() == Report.StatusEnum.PENDING ? report : null;
    }

    private static Report reportByReason(SessionApi admin, String reportedUsername, String reason) {
        try {
            var page = admin.api().adminListReports(reportedUsername, null, null, "createdAt", "desc", null, 100);
            if (page == null || page.getItems() == null) return null;
            return page.getItems().stream().filter(report -> reason.equals(report.getReason())).findFirst().orElse(null);
        } catch (Exception exception) {
            throw new AssertionError("Could not read reports from the live server.", exception);
        }
    }

    private static AdminUser userById(SessionApi admin, UUID id) {
        try {
            return admin.api().adminGetUser(id);
        } catch (Exception exception) {
            return null;
        }
    }

    private static AdminUser userByUsername(SessionApi admin, String username) {
        try {
            var page = admin.api().adminListUsers(username, null, "createdAt", "desc", null, 20);
            if (page == null || page.getItems() == null) return null;
            return page.getItems().stream().filter(user -> username.equals(user.getUsername())).findFirst().orElse(null);
        } catch (Exception exception) {
            return null;
        }
    }

    private static void cleanup(SessionApi admin, AdminUser... users) {
        for (AdminUser user : users) {
            if (user == null) continue;
            try {
                admin.api().adminDeleteUser(user.getId());
            } catch (Exception ignored) {
                // Best-effort cleanup; a failed delete is reported by the server logs.
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectScreen(Stage stage, AdminScreen screen) {
        onFx(() -> {
            ListView navigation = (ListView) stage.getScene().lookup(".admin-nav");
            navigation.getSelectionModel().select(screen);
            return true;
        });
    }

    private static void selectRow(Stage stage, int tableIndex, String firstColumnText) {
        onFx(() -> {
            int index = rowIndex(stage, tableIndex, firstColumnText);
            assertThat(index).as("row %s in table %s", firstColumnText, tableIndex).isGreaterThanOrEqualTo(0);
            table(stage, tableIndex).getSelectionModel().select(index);
            return true;
        });
    }

    private static int rowIndex(Stage stage, int tableIndex, String firstColumnText) {
        TableView<?> table = table(stage, tableIndex);
        if (table == null) return -1;
        for (int i = 0; i < table.getItems().size(); i++) {
            Object value = table.getColumns().getFirst().getCellData(i);
            if (value != null && firstColumnText.equals(value.toString())) return i;
        }
        return -1;
    }

    private static boolean tableContains(Stage stage, int tableIndex, String text) {
        TableView<?> table = table(stage, tableIndex);
        if (table == null) return false;
        for (int row = 0; row < table.getItems().size(); row++) {
            for (int column = 0; column < table.getColumns().size(); column++) {
                Object value = table.getColumns().get(column).getCellData(row);
                if (value != null && text.equals(value.toString())) return true;
            }
        }
        return false;
    }

    private static int tableRows(Stage stage, int index) {
        TableView<?> table = table(stage, index);
        return table == null ? 0 : table.getItems().size();
    }

    private static TableView<?> table(Stage stage, int index) {
        if (stage.getScene() == null) return null;
        List<TableView<?>> tables = new java.util.ArrayList<>();
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".table-view")) {
            if (node instanceof TableView<?> candidate) tables.add(candidate);
        }
        return index < tables.size() ? tables.get(index) : null;
    }

    private static boolean confirmVisible(Stage stage, String id) {
        return stage.getScene() != null && stage.getScene().lookup(id) != null
                && stage.getScene().lookup(id).isVisible();
    }

    private static boolean labelContaining(Stage stage, String text) {
        if (stage.getScene() == null) return false;
        return stage.getScene().getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()) && candidate.isVisible())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX button: " + text));
    }

    private static Button buttonById(Scene scene, String id) {
        return (Button) scene.lookup(id);
    }

    private static TextField field(Scene scene, String prompt) {
        TextField field = fieldOrNull(scene, prompt);
        if (field == null) throw new AssertionError("Missing JavaFX field: " + prompt);
        return field;
    }

    private static TextField fieldOrNull(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst().orElse(null);
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep();
        }
        throw new AssertionError("Timed out waiting for the live admin state.");
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The shell can still be swapping views.
            }
            sleep();
        }
        throw new AssertionError("Timed out waiting for the live JavaFX UI.");
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the live server.", exception);
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
                throw new IllegalStateException("JavaFX toolkit did not start within 30 seconds.");
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
