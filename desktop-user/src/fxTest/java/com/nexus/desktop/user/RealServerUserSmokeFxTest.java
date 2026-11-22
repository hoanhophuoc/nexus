package com.nexus.desktop.user;

import com.nexus.desktop.shared.SessionApi;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.Theme;
import com.nexus.desktop.shared.ui.ThemeManager;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.CreateDirectConversationRequest;
import com.nexus.sdk.model.DecideFriendRequestRequest;
import com.nexus.sdk.model.Login;
import com.nexus.sdk.model.Message;
import com.nexus.sdk.model.MyProfile;
import com.nexus.sdk.model.Registration;
import com.nexus.sdk.model.SendFriendRequestRequest;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the user client against a real server. It creates its own two accounts and a
 * friendship between them through the API, signs both into their own client windows through the same
 * code path the application uses, sends a message from the first, waits for the second to receive it,
 * and moves the shell between themes. It is the one check that proves the packaged client's stack —
 * SDK, session cookie, encrypted cache and FXML shell — works against a running service, in both
 * directions, without clicking anything.
 *
 * <pre>
 * NEXUS_REAL_SERVER=true ./gradlew :desktop-user:fxTest --tests '*RealServerUserSmokeFxTest'
 * </pre>
 *
 * <p>{@code scripts/smoke-desktop.sh} starts the database, Redis and the server, runs this, and tears
 * them down. The origin defaults to {@code http://127.0.0.1:8080} and can be set with
 * {@code NEXUS_REAL_SERVER_ORIGIN}.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_REAL_SERVER", matches = "true")
class RealServerUserSmokeFxTest {
    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void sendsAMessageAndRendersItForTheOtherSide(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        String origin = env("NEXUS_REAL_SERVER_ORIGIN", "http://127.0.0.1:8080");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "smoke_" + suffix;
        String peerUsername = "smoke_peer_" + suffix;
        String peerName = "Smoke Peer " + suffix;
        try {
            System.out.println("SMOKE registering " + username + " and its counterpart at " + origin);
            SessionApi session = new SessionApi(URI.create(origin));
            MyProfile self = register(session, username, "Smoke Client");
            SessionApi peerApi = new SessionApi(URI.create(origin));
            MyProfile counterpart = register(peerApi, peerUsername, peerName);
            Conversation direct = befriend(session, peerApi, self, counterpart);
            System.out.println("SMOKE direct conversation " + direct.getId() + " with " + peerName);

            System.out.println("SMOKE signing in as " + username);
            try (Client sender = new Client(origin, username, Theme.LIGHT, 1180, 760)) {
                // Connected is written last by the shell's load, so waiting for it means the server
                // round trip, the profile and the conversation list all finished.
                sender.awaitConnected();
                assertThat(onFx(() -> sender.shell.lookupAll(".list-view")))
                        .as("the conversation list renders").isNotEmpty();
                System.out.println("SMOKE shell connected");

                // Both accounts are new, so the shell's list holds exactly one conversation; its
                // server-side name is null for a direct chat, so the single row is selected directly.
                waitForFx(() -> selectFirstConversation(sender.shell), Duration.ofSeconds(20));
                waitForFx(() -> sender.shell.lookup("#composeField") instanceof TextField field
                        && field.isVisible() && !field.isDisabled(), Duration.ofSeconds(10));

                String message = "smoke ping " + suffix;
                onFx(() -> {
                    ((TextField) sender.shell.lookup("#composeField")).setText(message);
                    ((Button) sender.shell.lookup("#sendButton")).fire();
                    return true;
                });
                // A queued message renders at once with a "pending" bubble; the delivered one is the
                // same text without that marker, so this waits for the server to acknowledge the send.
                waitForFx(() -> bubbleState(sender.shell, message).equals("sent"),
                        Duration.ofSeconds(60));
                assertThat(bubbleState(sender.shell, message))
                        .as("the sent message renders as a delivered bubble").isEqualTo("sent");
                // The bubble stops being pending only once the queued message was uploaded, so the
                // server's own history is the other half of the round trip.
                assertThat(session.api().getMessages(direct.getId(), null, 50).getItems())
                        .extracting(Message::getContent)
                        .as("the server stored the message").contains(message);
                System.out.println("SMOKE message delivered to the sender: " + message);

                System.out.println("SMOKE signing in as " + peerUsername + " to receive it");
                try (Client receiver = new Client(origin, peerUsername, Theme.DARK, 1000, 700)) {
                    receiver.awaitConnected();
                    waitForFx(() -> selectFirstConversation(receiver.shell), Duration.ofSeconds(20));
                    // Opening the chat fetches the conversation's history from the server, so this is
                    // the receive path: the other account's message arriving in its own client.
                    waitForFx(() -> bubbleState(receiver.shell, message).equals("sent"),
                            Duration.ofSeconds(60));
                    assertThat(bubbleClass(receiver.shell, message))
                            .as("the receiver renders the sender's message, not its own")
                            .contains("theirs").doesNotContain("mine");
                    System.out.println("SMOKE peer received: " + message);
                }

                onFx(() -> {
                    sender.theme.setTheme(Theme.DARK);
                    return true;
                });
                waitForFx(() -> sender.shell.getStyleClass().contains("theme-dark"),
                        Duration.ofSeconds(10));
                assertThat(sender.theme.isDark()).as("the client reports the dark theme").isTrue();

                onFx(() -> {
                    sender.theme.setTheme(Theme.LIGHT);
                    return true;
                });
                waitForFx(() -> sender.shell.getStyleClass().contains("theme-light"),
                        Duration.ofSeconds(10));
                System.out.println("SMOKE theme switched light -> dark -> light");
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    /** One signed-in client window: its own session, cache, theme and shell, as the application builds them. */
    private static final class Client implements AutoCloseable {
        final Stage stage;
        final ThemeManager theme;
        final I18n i18n;
        final UserAppContext context;
        final Parent shell;

        Client(String origin, String username, Theme start, int width, int height) throws Exception {
            stage = onFx(Stage::new);
            theme = new ThemeManager();
            theme.setTheme(start);
            i18n = new I18n(BASE, Locale.ENGLISH);
            context = new UserAppContext(stage, origin, false, null, theme, i18n);
            context.activate(context.signIn(username, PASSWORD.toCharArray()));
            shell = onFx(() -> mount(stage, theme,
                    ViewLoader.load(ViewLoader.SHELL, new ShellController(context, () -> { }), i18n),
                    width, height));
        }

        void awaitConnected() {
            waitForFx(() -> shell.lookup("#statusLabel") instanceof Label status
                    && i18n.get("shell.connected").equals(status.getText()), Duration.ofSeconds(30));
        }

        @Override
        public void close() {
            onFx(() -> {
                stage.close();
                context.close();
                return true;
            });
        }
    }

    /** Registers the account and signs its API session in: registration alone issues no session cookie. */
    private static MyProfile register(SessionApi session, String username, String fullName)
            throws Exception {
        session.api().getCsrfToken();
        MyProfile profile = session.api().register(new Registration()
                .username(username)
                .fullName(fullName)
                .email(username + "@example.test")
                .address("1 Smoke Road")
                .birthDate(LocalDate.of(1990, 1, 1))
                .gender(Registration.GenderEnum.OTHER)
                .password(PASSWORD));
        session.api().getCsrfToken();
        session.api().login(new Login().username(username).password(PASSWORD));
        return profile;
    }

    /** A direct chat is only allowed between friends, so the counterpart accepts before it is created. */
    private static Conversation befriend(SessionApi session, SessionApi peer, MyProfile self,
                                         MyProfile counterpart) throws Exception {
        session.api().sendFriendRequest(new SendFriendRequestRequest().userId(counterpart.getId()));
        peer.api().decideFriendRequest(
                peer.api().listFriendRequests(null, 10).getItems().getFirst().getId(),
                new DecideFriendRequestRequest().decision(DecideFriendRequestRequest.DecisionEnum.ACCEPT));
        return session.api().createDirectConversation(
                new CreateDirectConversationRequest().participantId(counterpart.getId()));
    }

    private static boolean selectFirstConversation(Parent shell) {
        for (Node node : shell.lookupAll(".list-view")) {
            if (!(node instanceof ListView<?> list) || list.getItems().isEmpty()) continue;
            if (!(list.getItems().getFirst() instanceof Conversation)) continue;
            list.getSelectionModel().clearAndSelect(0);
            return true;
        }
        return false;
    }

    /** {@code "absent"}, {@code "pending"} while queued, or {@code "sent"} once the server took it. */
    private static String bubbleState(Parent shell, String text) {
        Node bubble = bubbleShowing(shell, text);
        if (bubble == null) return "absent";
        return bubble.getStyleClass().contains("pending") ? "pending" : "sent";
    }

    /** The bubble's own classes, which say whose message the reader sees and whether it is queued. */
    private static java.util.List<String> bubbleClass(Parent shell, String text) {
        Node bubble = bubbleShowing(shell, text);
        return bubble == null ? java.util.List.of() : java.util.List.copyOf(bubble.getStyleClass());
    }

    private static Node bubbleShowing(Parent shell, String text) {
        for (Node bubble : shell.lookupAll(".message-bubble")) {
            boolean shows = bubble.lookupAll(".label").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .anyMatch(label -> text.equals(label.getText()));
            if (shows) return bubble;
        }
        return null;
    }

    private static Parent mount(Stage stage, ThemeManager theme, Parent root, int width, int height) {
        Scene scene = new Scene(root, width, height);
        theme.apply(scene);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return root;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
