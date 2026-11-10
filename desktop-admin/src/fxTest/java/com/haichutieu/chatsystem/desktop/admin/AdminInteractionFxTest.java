package com.haichutieu.chatsystem.desktop.admin;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.chart.BarChart;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class AdminInteractionFxTest {

    @Test
    void signsInAndExercisesEveryAdminWorkspaceThroughTheFxmlShell() throws Exception {
        try (FakeAdminServer server = new FakeAdminServer()) {
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<AdminDesktopApplication> applicationReference = new AtomicReference<>();
            onFx(() -> {
                AdminDesktopApplication application = new AdminDesktopApplication(server.origin().toString());
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
                    field(scene, "Administrator username").setText("admin");
                    field(scene, "Password").setText("correct horse battery staple");
                    button(scene, "Sign in").fire();
                    return true;
                });
                waitForFx(() -> stage.getScene() != null && stage.getScene().lookup(".admin-nav") != null,
                        Duration.ofSeconds(10));
                assertThat(server.loginUsername()).isEqualTo("admin");
                assertThat(server.loginPassword()).isEqualTo("correct horse battery staple");
                waitForFx(() -> tableRows(stage, 0) > 0, Duration.ofSeconds(10));
                assertThat(labelContaining(stage, "Alice Admin")).isTrue();
                assertThat(((TextField) stage.getScene().lookup("#filterField")).getAccessibleText())
                        .isNotBlank();

                onFx(() -> {
                    button(stage.getScene(), "Lock").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                assertThat(server.lockedUsers()).doesNotContain(FakeAdminServer.USER_ID);
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelActionButton").fire();
                    return true;
                });
                waitForFx(() -> !confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                assertThat(server.lockedUsers()).doesNotContain(FakeAdminServer.USER_ID);
                onFx(() -> {
                    button(stage.getScene(), "Lock").fire();
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitForFx(() -> server.lockedUsers().contains(FakeAdminServer.USER_ID), Duration.ofSeconds(10));
                waitForFx(() -> button(stage, "Unlock") != null, Duration.ofSeconds(10));

                onFx(() -> {
                    button(stage.getScene(), "New user").fire();
                    field(stage.getScene(), "Username").setText("carol");
                    field(stage.getScene(), "Full name").setText("Carol Example");
                    field(stage.getScene(), "Email").setText("carol@example.test");
                    field(stage.getScene(), "Address").setText("4 Example Street");
                    DatePicker birthDate = (DatePicker) stage.getScene().lookup("#birthDateField");
                    birthDate.setValue(java.time.LocalDate.of(1994, 3, 5));
                    ComboBox<?> gender = (ComboBox<?>) stage.getScene().lookup("#genderField");
                    gender.getSelectionModel().select(1);
                    field(stage.getScene(), "At least 12 characters").setText("correct horse battery staple");
                    button(stage.getScene(), "Save").fire();
                    return true;
                });
                waitForFx(() -> "carol".equals(server.createdUsername()), Duration.ofSeconds(10));
                waitForFx(() -> tableContains(stage, 0, "carol"), Duration.ofSeconds(10));
                waitForFx(() -> tableRows(stage, 1) > 0, Duration.ofSeconds(10));
                waitForFx(() -> tableRows(stage, 2) > 0, Duration.ofSeconds(10));

                onFx(() -> {
                    field(stage.getScene(), "Full name").setText("Carol Updated");
                    button(stage.getScene(), "Save").fire();
                    return true;
                });
                waitForFx(() -> "Carol Updated".equals(server.updatedFullName()), Duration.ofSeconds(10));
                waitForFx(() -> "Carol Updated".equals(field(stage.getScene(), "Full name").getText()),
                        Duration.ofSeconds(10));
                waitForFx(() -> selectedTableContains(stage, 0, "Carol Updated")
                                && !buttonById(stage.getScene(), "#resetPasswordButton").isDisabled(),
                        Duration.ofSeconds(10));

                Button resetPassword = button(stage.getScene(), "Reset password");
                onFx(() -> {
                    resetPassword.requestFocus();
                    resetPassword.fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelActionButton").fire();
                    return true;
                });
                assertThat(server.resetRequested(server.createdUserId())).isFalse();
                waitForFx(resetPassword::isFocused, Duration.ofSeconds(10));
                onFx(() -> {
                    button(stage.getScene(), "Reset password").fire();
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitForFx(() -> server.resetRequested(server.createdUserId()), Duration.ofSeconds(10));

                onFx(() -> {
                    button(stage.getScene(), "Delete").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelActionButton").fire();
                    return true;
                });
                waitForFx(() -> !confirmVisible(stage, "#actionConfirmBox"), Duration.ofSeconds(10));
                assertThat(server.deleted(server.createdUserId())).isFalse();
                onFx(() -> {
                    button(stage.getScene(), "Delete").fire();
                    buttonById(stage.getScene(), "#confirmActionButton").fire();
                    return true;
                });
                waitForFx(() -> server.deleted(server.createdUserId()), Duration.ofSeconds(10));
                waitForFx(() -> !tableContains(stage, 0, "carol"), Duration.ofSeconds(10));

                selectScreen(stage, AdminScreen.REPORTS);
                waitForFx(() -> tableContains(stage, 0, "Harassment in a direct conversation"),
                        Duration.ofSeconds(10));
                waitForFx(() -> tableContains(stage, 1, "I will find you after class."),
                        Duration.ofSeconds(10));
                waitForFx(() -> labelContaining(stage, "Evidence for mallory"), Duration.ofSeconds(10));
                assertThat(((TextField) stage.getScene().lookup("#reportedUsernameField"))
                        .getAccessibleText()).isNotBlank();
                assertThat(((DatePicker) stage.getScene().lookup("#fromDate")).getAccessibleText())
                        .isNotBlank();
                assertThat(((TextField) stage.getScene().lookup("#noteField")).getAccessibleText())
                        .isNotBlank();
                onFx(() -> {
                    button(stage.getScene(), "View evidence").fire();
                    return true;
                });
                waitForFx(() -> button(stage, "Uphold") != null, Duration.ofSeconds(10));
                onFx(() -> {
                    button(stage.getScene(), "Uphold").fire();
                    return true;
                });
                waitForFx(() -> confirmVisible(stage, "#decisionConfirmBox"), Duration.ofSeconds(10));
                assertThat(server.reportDecision()).isNull();
                onFx(() -> {
                    buttonById(stage.getScene(), "#cancelDecisionButton").fire();
                    return true;
                });
                assertThat(server.reportDecision()).isNull();
                onFx(() -> {
                    button(stage.getScene(), "Uphold").fire();
                    buttonById(stage.getScene(), "#confirmDecisionButton").fire();
                    return true;
                });
                waitForFx(() -> "uphold".equals(server.reportDecision()), Duration.ofSeconds(10));

                selectScreen(stage, AdminScreen.GROUPS);
                waitForFx(() -> tableContains(stage, 0, "Moderators"), Duration.ofSeconds(10));
                waitForFx(() -> tableContains(stage, 1, "Bob Reporter"), Duration.ofSeconds(10));
                assertTablesNamed(stage, 2);

                selectScreen(stage, AdminScreen.REGISTRATIONS);
                waitForFx(() -> tableContains(stage, 0, "alice@example.test"), Duration.ofSeconds(10));
                assertTablesNamed(stage, 1);

                selectScreen(stage, AdminScreen.ANALYTICS);
                waitForFx(() -> chartTitled(stage, "Registrations by month"), Duration.ofSeconds(10));
                waitForFx(() -> chartTitled(stage, "App opens by month"), Duration.ofSeconds(10));
                onFx(() -> {
                    button(stage.getScene(), "Load activity").fire();
                    button(stage.getScene(), "Load friend report").fire();
                    return true;
                });
                waitForFx(() -> tableRows(stage, 0) > 0 && tableRows(stage, 1) > 0,
                        Duration.ofSeconds(10));
                assertTablesNamed(stage, 2);

                selectScreen(stage, AdminScreen.AUDIT);
                waitForFx(() -> tableContains(stage, 0, "admin.user.lock"), Duration.ofSeconds(10));
                assertTablesNamed(stage, 1);

                waitForFx(() -> button(stage, "Sign out") != null, Duration.ofSeconds(10));
                onFx(() -> {
                    button(stage.getScene(), "Sign out").fire();
                    return true;
                });
                waitForFx(() -> stage.getScene() != null
                        && fieldOrNull(stage.getScene(), "Administrator username") != null,
                        Duration.ofSeconds(10));
                waitForFx(() -> server.logoutCount() > 0, Duration.ofSeconds(10));
                assertThat(server.logoutCount()).isGreaterThan(0);
            } finally {
                onFx(() -> {
                    stage.close();
                    application.stop();
                    return true;
                });
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

    @SuppressWarnings("rawtypes")
    private static int tableRows(Stage stage, int index) {
        TableView<?> table = table(stage, index);
        return table == null ? 0 : table.getItems().size();
    }

    private static void assertTablesNamed(Stage stage, int count) {
        onFx(() -> {
            for (int index = 0; index < count; index++) {
                TableView<?> table = table(stage, index);
                assertThat(table).as("table %s", index).isNotNull();
                assertThat(table.getAccessibleText()).as("table %s name", index).isNotBlank();
            }
            return true;
        });
    }

    private static boolean tableContains(Stage stage, int index, String text) {
        TableView<?> table = table(stage, index);
        return table != null && table.getItems().stream()
                .anyMatch(item -> item != null && item.toString().contains(text));
    }

    private static boolean selectedTableContains(Stage stage, int index, String text) {
        TableView<?> table = table(stage, index);
        Object selected = table == null ? null : table.getSelectionModel().getSelectedItem();
        return selected != null && selected.toString().contains(text);
    }

    @SuppressWarnings("rawtypes")
    private static TableView<?> table(Stage stage, int index) {
        if (stage.getScene() == null) return null;
        java.util.List<TableView> tables = stage.getScene().getRoot().lookupAll(".table-view").stream()
                .filter(TableView.class::isInstance).map(TableView.class::cast).toList();
        return index < tables.size() ? tables.get(index) : null;
    }

    private static boolean chartTitled(Stage stage, String title) {
        if (stage.getScene() == null) return false;
        return stage.getScene().getRoot().lookupAll(".admin-chart").stream()
                .filter(BarChart.class::isInstance).map(BarChart.class::cast)
                .anyMatch(chart -> title.equals(chart.getTitle()));
    }

    private static Button button(Stage stage, String text) {
        return stage.getScene() == null ? null : button(stage.getScene(), text);
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText())).findFirst().orElse(null);
    }

    private static boolean confirmVisible(Stage stage, String id) {
        return stage.getScene() != null && stage.getScene().lookup(id) != null
                && stage.getScene().lookup(id).isVisible();
    }

    private static Button buttonById(Scene scene, String id) {
        Button button = (Button) scene.lookup(id);
        if (button == null) throw new AssertionError("Missing JavaFX button: " + id);
        return button;
    }

    private static TextField field(Scene scene, String prompt) {
        TextField field = fieldOrNull(scene, prompt);
        if (field == null) throw new AssertionError("Missing JavaFX field: " + prompt);
        return field;
    }

    private static TextField fieldOrNull(Scene scene, String prompt) {
        return scene == null ? null : scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText())).findFirst().orElse(null);
    }

    private static boolean labelContaining(Stage stage, String text) {
        if (stage.getScene() == null) return false;
        return stage.getScene().getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (Throwable failure) {
                last = failure;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the JavaFX UI.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the JavaFX UI.", last);
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
}
