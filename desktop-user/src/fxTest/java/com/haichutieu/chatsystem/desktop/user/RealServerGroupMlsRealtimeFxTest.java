package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.SessionApi;
import com.haichutieu.chatsystem.desktop.shared.cache.CacheKeyDerivation;
import com.haichutieu.chatsystem.desktop.shared.cache.CachedMessage;
import com.haichutieu.chatsystem.desktop.shared.cache.EncryptedMessageCache;
import com.haichutieu.chatsystem.desktop.shared.messaging.MlsGroupSyncService;
import com.haichutieu.chatsystem.desktop.shared.messaging.RealtimeConnection;
import com.haichutieu.chatsystem.sdk.model.Conversation;
import com.haichutieu.chatsystem.sdk.model.Login;
import com.haichutieu.chatsystem.sdk.model.MlsMessage;
import com.haichutieu.chatsystem.sdk.model.MyProfile;
import com.haichutieu.chatsystem.sdk.model.MlsMessagePage;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Opt-in two-account real-server smoke test.
 *
 * <pre>
 * CHAT_REAL_SERVER=true CHAT_REAL_USERNAME=alice_real CHAT_REAL_PASSWORD=... \
 * CHAT_REAL_BOB_USERNAME=bob_real CHAT_REAL_BOB_PASSWORD=... \
 * DISPLAY=:0 ./gradlew :desktop-user:fxTest --tests '*RealServerGroupMlsRealtimeFxTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHAT_REAL_SERVER", matches = "true")
class RealServerGroupMlsRealtimeFxTest {

    @Test
    void aliceSendsGroupMessageAndBobDecryptsItFromRealtime(@TempDir Path home) throws Exception {
        String origin = env("CHAT_REAL_SERVER_ORIGIN", "http://127.0.0.1:8080");
        String aliceName = env("CHAT_REAL_USERNAME", "alice_real");
        String alicePassword = required("CHAT_REAL_PASSWORD");
        String bobName = env("CHAT_REAL_BOB_USERNAME", "bob_real");
        String bobPassword = required("CHAT_REAL_BOB_PASSWORD");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());

        SessionApi aliceSession = null;
        SessionApi bobSession = null;
        EncryptedMessageCache bobCache = null;
        RealtimeConnection bobRealtime = null;
        AtomicReference<Stage> stageReference = new AtomicReference<>();
        AtomicReference<UserDesktopApplication> applicationReference = new AtomicReference<>();
        try {
            SignedIn alice = login(origin, aliceName, alicePassword);
            aliceSession = alice.session();
            SignedIn bob = login(origin, bobName, bobPassword);
            bobSession = bob.session();
            UUID aliceId = alice.profile().getId();
            UUID bobId = bob.profile().getId();

            Path bobState = home.resolve("bob-mls");
            Files.createDirectories(bobState);
            SecretKey bobKey = CacheKeyDerivation.derive(bobPassword.toCharArray(), bobId);
            try (EncryptedMessageCache publishCache = new EncryptedMessageCache(
                    bobState.resolve("history.bin"), bobId, bobKey)) {
                MlsGroupSyncService publisher = new MlsGroupSyncService(bobSession.api(), publishCache,
                        bobState.resolve("mls-state.bin"), bobId, bobKey);
                publisher.publishKeyPackage();
            }

            Path aliceState = home.resolve(".chat-system").resolve(aliceId.toString());
            Files.createDirectories(aliceState);
            SecretKey aliceKey = CacheKeyDerivation.derive(alicePassword.toCharArray(), aliceId);
            Conversation group;
            try (EncryptedMessageCache aliceCache = new EncryptedMessageCache(
                    aliceState.resolve("history.bin"), aliceId, aliceKey)) {
                MlsGroupSyncService aliceGroups = new MlsGroupSyncService(aliceSession.api(), aliceCache,
                        aliceState.resolve("mls-state.bin"), aliceId, aliceKey);
                String name = "Real Team " + UUID.randomUUID().toString().substring(0, 8);
                group = aliceGroups.createGroup(name, List.of(bobId));
            }

            bobCache = new EncryptedMessageCache(bobState.resolve("history.bin"), bobId, bobKey);
            MlsGroupSyncService bobGroups = new MlsGroupSyncService(bobSession.api(), bobCache,
                    bobState.resolve("mls-state.bin"), bobId, bobKey);
            bobGroups.syncGroup(group.getId());
            EncryptedMessageCache bobCacheForRealtime = bobCache;
            MlsGroupSyncService bobGroupsForRealtime = bobGroups;

            CountDownLatch bobConnected = new CountDownLatch(1);
            CountDownLatch bobDecrypted = new CountDownLatch(1);
            AtomicReference<Throwable> realtimeFailure = new AtomicReference<>();
            String content = "real-group-" + UUID.randomUUID();
            RealtimeConnection connection = new RealtimeConnection(bobSession, event -> {
                try {
                    if (bobGroupsForRealtime.handleRealtimeEvent(event)
                            && bobCacheForRealtime.messagesInConversation(group.getId()).stream()
                            .map(CachedMessage::content).anyMatch(content::equals)) {
                        bobDecrypted.countDown();
                    }
                } catch (Exception exception) {
                    realtimeFailure.set(exception);
                    bobDecrypted.countDown();
                }
            }, bobConnected::countDown);
            bobRealtime = connection;
            connection.start();
            assertThat(bobConnected.await(10, TimeUnit.SECONDS))
                    .as("Bob realtime connection").isTrue();

            onFx(() -> {
                UserDesktopApplication application = new UserDesktopApplication(origin, false);
                applicationReference.set(application);
                Stage stage = new Stage();
                stageReference.set(stage);
                application.start(stage);
                return true;
            });
            Stage stage = stageReference.get();
            onFx(() -> {
                Scene scene = stage.getScene();
                field(scene, "Username").setText(aliceName);
                field(scene, "Password").setText(alicePassword);
                button(scene, "Sign in").fire();
                return true;
            });
            waitForFx(() -> button(stage, "Send") != null, Duration.ofSeconds(15));
            waitForFx(() -> conversationNamed(stage, group.getName()), Duration.ofSeconds(15));
            onFx(() -> {
                selectConversation(stage, group.getName());
                return true;
            });
            onFx(() -> {
                Scene scene = stage.getScene();
                field(scene, "Write a message").setText(content);
                button(scene, "Send").fire();
                return true;
            });
            waitForFx(() -> labelContaining(stage, content), Duration.ofSeconds(15));
            assertThat(bobDecrypted.await(15, TimeUnit.SECONDS))
                    .as("Bob receives the realtime MLS message").isTrue();
            if (realtimeFailure.get() != null) throw new AssertionError(realtimeFailure.get());

            MlsMessagePage serverMessages = aliceSession.api().getMlsMessages(group.getId(), null, 100);
            assertThat(serverMessages.getItems()).isNotEmpty();
            assertThat(serverMessages.getItems()).anySatisfy(message -> {
                if (message.getKind() == MlsMessage.KindEnum.APPLICATION) {
                    assertThat(new String(message.getCiphertext(), StandardCharsets.ISO_8859_1))
                            .doesNotContain(content);
                }
            });

            onFx(() -> {
                stage.close();
                applicationReference.get().stop();
                return true;
            });
        } finally {
            if (bobRealtime != null) bobRealtime.close();
            if (bobCache != null) bobCache.close();
            if (aliceSession != null) aliceSession.clear();
            if (bobSession != null) bobSession.clear();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static SignedIn login(String origin, String username, String password) throws Exception {
        SessionApi session = new SessionApi(URI.create(origin));
        session.api().getCsrfToken();
        MyProfile profile = session.api().login(new Login().username(username).password(password));
        return new SignedIn(session, profile);
    }

    private record SignedIn(SessionApi session, MyProfile profile) {
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
                Thread.sleep(100);
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

    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value).as(name).isNotBlank();
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
