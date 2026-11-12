package com.nexus.desktop.user;

import com.nexus.desktop.shared.SessionApi;
import com.nexus.desktop.shared.cache.CacheKeyDerivation;
import com.nexus.desktop.shared.cache.EncryptedMessageCache;
import com.nexus.desktop.shared.messaging.MlsGroupSyncService;
import com.nexus.desktop.shared.mls.MlsApplicationMessage;
import com.nexus.desktop.shared.mls.MlsGroupCrypto;
import com.nexus.desktop.shared.mls.MlsGroupState;
import com.nexus.desktop.shared.mls.MlsIdentity;
import com.nexus.desktop.shared.mls.MlsKeyPackage;
import com.nexus.sdk.model.Conversation;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class GroupChatMlsRealtimeFxTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void sendsEncryptedGroupMessageAndReceivesRealtimeMlsEvent(@TempDir Path home)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        MlsIdentity friendIdentity = MlsIdentity.generate(FakeChatServer.FRIEND_ID, "desktop-friend");
        byte[] friendKeyPackage = MlsKeyPackage.create(friendIdentity).encode();
        try (FakeChatServer server = new FakeChatServer(friendKeyPackage);
             FakeWebSocketServer realtime = new FakeWebSocketServer()) {
            setupExistingGroup(home, server);
            server.hideDirectConversation();

            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<UserDesktopApplication> applicationReference = new AtomicReference<>();
            onFx(() -> {
                UserDesktopApplication application = new UserDesktopApplication(
                        server.origin().toString(), true, realtime.uri());
                applicationReference.set(application);
                Stage stage = new Stage();
                stageReference.set(stage);
                application.start(stage);
                return true;
            });
            Stage stage = stageReference.get();
            UserDesktopApplication application = applicationReference.get();
            try {
                signIn(stage);
                waitForFx(() -> conversationNamed(stage, FakeChatServer.GROUP_NAME),
                        Duration.ofSeconds(10));
                onFx(() -> selectConversation(stage, FakeChatServer.GROUP_NAME));
                realtime.awaitClient(TimeUnit.SECONDS, 10);
                onFx(() -> selectConversation(stage, FakeChatServer.GROUP_NAME));

                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Write a message").setText("group secret from UI");
                    button(scene, "Send").fire();
                    return true;
                });

                waitForFx(() -> labelContaining(stage, "group secret from UI"),
                        Duration.ofSeconds(10));
                assertThat(server.sentContents()).isEmpty();
                assertThat(server.groupCiphertexts()).isNotEmpty();
                assertThat(server.groupCiphertexts()).allSatisfy(ciphertext ->
                        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1))
                                .doesNotContain("group secret from UI"));

                byte[] commit = server.firstCommit();
                assertThat(commit).isNotNull();
                MlsGroupState friendState = MlsGroupCrypto.applyCommit(
                        null, FakeChatServer.GROUP_ID, 1L, friendIdentity, commit);
                MlsApplicationMessage incoming = MlsGroupCrypto.encrypt(friendState, friendIdentity,
                        "live group message".getBytes(StandardCharsets.UTF_8));
                server.addGroupApplication(incoming.encode(), 1L);
                realtime.sendText("{\"type\":\"MlsMessage\",\"conversationId\":\""
                        + FakeChatServer.GROUP_ID + "\"}");
                waitForFx(() -> labelContaining(stage, "live group message"), Duration.ofSeconds(10));
            } finally {
                onFx(() -> {
                    stage.close();
                    application.stop();
                    return true;
                });
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static void setupExistingGroup(Path home, FakeChatServer server) throws Exception {
        SecretKey key = CacheKeyDerivation.derive(PASSWORD.toCharArray(), FakeChatServer.USER_ID);
        Path account = home.resolve(".nexus").resolve(FakeChatServer.USER_ID.toString());
        Files.createDirectories(account);
        SessionApi session = new SessionApi(server.origin());
        try (EncryptedMessageCache cache = new EncryptedMessageCache(
                account.resolve("history.bin"), FakeChatServer.USER_ID, key)) {
            MlsGroupSyncService groups = new MlsGroupSyncService(session.api(), cache,
                    account.resolve("mls-state.bin"), FakeChatServer.USER_ID, key);
            Conversation created = groups.createGroup(FakeChatServer.GROUP_NAME,
                    List.of(FakeChatServer.FRIEND_ID));
            assertThat(created.getId()).isEqualTo(FakeChatServer.GROUP_ID);
        } finally {
            session.clear();
        }
    }

    private static void signIn(Stage stage) {
        onFx(() -> {
            Scene scene = stage.getScene();
            field(scene, "Username").setText("alice");
            field(scene, "Password").setText(PASSWORD);
            button(scene, "Sign in").fire();
            return true;
        });
        waitForFx(() -> button(stage, "Send") != null, Duration.ofSeconds(10));
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX button: " + text));
    }

    private static Button button(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return null;
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElse(null);
    }

    private static boolean conversationNamed(Stage stage, String name) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".list-view").stream()
                .filter(ListView.class::isInstance)
                .map(node -> (ListView<?>) node)
                .flatMap(list -> list.getItems().stream())
                .filter(Conversation.class::isInstance)
                .map(Conversation.class::cast)
                .anyMatch(conversation -> name.equals(conversation.getName()));
    }

    private static boolean selectConversation(Stage stage, String name) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        for (javafx.scene.Node node : scene.getRoot().lookupAll(".list-view")) {
            if (!(node instanceof ListView<?> list)) continue;
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index) instanceof Conversation conversation
                        && name.equals(conversation.getName())) {
                    list.getSelectionModel().clearAndSelect(index);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean labelContaining(Stage stage, String text) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText() != null && label.getText().contains(text));
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
    }

    private static void waitForFx(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(onFx(condition::getAsBoolean))) return;
            } catch (AssertionError ignored) {
                // The scene can still be swapping from the login form to the chat shell.
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the JavaFX UI.", exception);
            }
        }
        throw new AssertionError("Timed out waiting for the JavaFX UI to update.");
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
