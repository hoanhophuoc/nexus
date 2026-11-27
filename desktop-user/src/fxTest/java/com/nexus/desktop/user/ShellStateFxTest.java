package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.MessageSearchResult;
import com.nexus.sdk.model.Conversation;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.nexus.desktop.shared.testing.FxThread.onFx;
import static com.nexus.desktop.shared.testing.FxThread.waitForFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One shell, driven through the states a user moves between, with the state written as a table so a
 * new case is a row rather than another 80-line test. Every row does one thing and waits for the
 * shell to settle, so a repaint that never happens fails the row that asked for it instead of the
 * teardown. Screenshots are not involved: these assertions are on the labels the shell actually shows.
 *
 * <p>A row's action runs on the test thread, because the two destructive actions open a
 * {@code showAndWait} dialog: the FX thread parks inside it, so the click that answers it has to come
 * from outside while {@link com.nexus.desktop.shared.testing.FxThread#waitForFx} polls through the
 * dialog's own nested event loop.
 */
class ShellStateFxTest {

    /** One state transition: what the user does, and what the shell must be showing once it settles. */
    private record Step(String state, Runnable action, BooleanSupplier settled) { }

    private static final String RENAMED_GROUP = "Renamed team";
    private static final String CREATED_GROUP = "Second team";
    private static final String THIRD_GROUP = "Third team";
    private static final String CAROL_MESSAGE = "marigold patch";
    private static final String GROUP_SUBTITLE = "Encrypted group conversation";
    private static final String DIRECT_SUBTITLE = "Private conversation";
    private static final String NO_CONVERSATION_TITLE = "Choose a conversation";
    private static final String NO_CONVERSATION_HINT =
            "Select a chat on the left, or open Friends to start a new one.";
    private static final String LOADING_MESSAGES = "Loading messages…";
    private static final String SEARCH_QUERY = "zebra";
    private static final String SEARCHED_MESSAGE = "zebra crossing";

    @Test
    void movesBetweenConversationsAndIgnoresAStaleRealtimeEvent(@TempDir Path home) throws Exception {
        runShellTable(home);
    }

    /**
     * Opt-in stress mode: the same table, repeated in this JVM. It is a separate test so the gate
     * keeps the suite's 60 second cap on the pass above — this method does not exist for the gate
     * unless {@code -Dnexus.shellTable.repeat=N} is set, and only then is its own timeout raised.
     *
     * <p>Each pass builds a fresh server, socket, window and cache directory, so a race that needs
     * a particular interleaving, or state left behind by an earlier shell, gets many chances without
     * a longer wait in any step and without external CPU load.
     */
    @Test
    @EnabledIfSystemProperty(named = "nexus.shellTable.repeat", matches = "\\d+")
    @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.MINUTES)
    void repeatedShellTableStress(@TempDir Path home) throws Exception {
        int iterations = Integer.getInteger("nexus.shellTable.repeat", 1);
        if (iterations < 1) {
            throw new IllegalArgumentException("nexus.shellTable.repeat must be at least 1, was " + iterations);
        }
        for (int iteration = 1; iteration <= iterations; iteration++) {
            Path iterationHome = home.resolve("iteration-" + iteration);
            Files.createDirectories(iterationHome);
            System.out.println("shell table iteration " + iteration + " of " + iterations);
            try {
                runShellTable(iterationHome);
            } catch (Exception | AssertionError failed) {
                throw new AssertionError("shell table iteration " + iteration + " of " + iterations
                        + " failed: " + failed.getMessage(), failed);
            }
        }
    }

    private void runShellTable(Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try (FakeChatServer server = new FakeChatServer();
             FakeWebSocketServer realtime = new FakeWebSocketServer()) {
            server.groupExists();
            server.addDirectMessage("from bob");
            server.addDirectMessageFrom(FakeChatServer.USER_ID, "mine to delete");
            server.addDirectMessageFrom(FakeChatServer.USER_ID, "mine to delete for everyone");
            AtomicReference<Stage> stageReference = new AtomicReference<>();
            AtomicReference<UserDesktopApplication> applicationReference = new AtomicReference<>();
            onFx(() -> {
                UserDesktopApplication application =
                        new UserDesktopApplication(server.origin().toString(), true, realtime.uri());
                applicationReference.set(application);
                Stage stage = new Stage();
                stageReference.set(stage);
                application.start(stage);
                return true;
            });
            Stage stage = stageReference.get();
            UserDesktopApplication application = applicationReference.get();
            try {
                onFx(() -> {
                    Scene scene = stage.getScene();
                    field(scene, "Username").setText("alice");
                    field(scene, "Password").setText("correct horse battery staple");
                    button(scene, "Sign in").fire();
                    return true;
                });
                waitForFx(() -> button(stage, "Send") != null, Duration.ofSeconds(10));
                realtime.awaitClient(TimeUnit.SECONDS, 10);
                // The shell selects the first conversation once its own refresh lands; the table starts
                // from that settled state instead of racing the opening one.
                waitForFx(() -> titleIs(stage, FakeChatServer.FRIEND_NAME), Duration.ofSeconds(10));

                AtomicBoolean timelineShowedLoading = new AtomicBoolean();
                AtomicBoolean reselectShowedLoading = new AtomicBoolean();
                List<Step> table = List.of(
                        new Step("open the group",
                                () -> assertThat(onFx(() -> selectConversation(stage, FakeChatServer.GROUP_NAME)))
                                        .isTrue(),
                                () -> titleIs(stage, FakeChatServer.GROUP_NAME)
                                        // The "direct chats only" guards are buttons that a group
                                        // disables, so this is the user-visible form of the rule: the
                                        // status string behind them is unreachable from the UI.
                                        && disabled(stage, "#clearHistoryButton")
                                        && disabled(stage, "#reportButton")),
                        new Step("rename the open group while the list refreshes",
                                () -> {
                                    // The settings dialog saves, the shell refreshes the conversation
                                    // list, and that refresh re-selects the chat that is already open.
                                    // It must carry the new name to the header and leave the timeline
                                    // alone; the watch records any swap of the timeline's children to
                                    // the loading placeholder, which a reopen would cause.
                                    watchForLoadingPlaceholder(stage, LOADING_MESSAGES, timelineShowedLoading);
                                    Platform.runLater(() -> button(stage, "Group settings").fire());
                                    Window settings = awaitDialog("#nameField");
                                    renameWhenReady(settings, RENAMED_GROUP);
                                },
                                () -> server.groupName().equals(RENAMED_GROUP)
                                        && titleIs(stage, RENAMED_GROUP)
                                        && subtitleIs(stage, GROUP_SUBTITLE)
                                        && !timelineShowedLoading.get()
                                        && !mentions(stage, LOADING_MESSAGES)),
                        new Step("switch to the direct chat and send",
                                () -> onFx(() -> {
                                    assertThat(selectConversation(stage, FakeChatServer.FRIEND_NAME)).isTrue();
                                    messageField(stage).setText("hello again");
                                    button(stage, "Send").fire();
                                    return true;
                                }),
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && mentions(stage, "hello again")
                                        // Exact, not a substring: the next row needs the row whose
                                        // text is exactly this, and "mine to delete for everyone"
                                        // also contains it, so `mentions` could pass while the row
                                        // this step asks for had not been rendered yet.
                                        && showsExactly(stage, "mine to delete")
                                        // The row's text is not the action: the delete item exists only
                                        // once the row is drawn as a stored message, so wait for the menu
                                        // the next step fires instead of for the label alone.
                                        && rowMenuOffers(stage, "mine to delete", "for me")),
                        new Step("select the open chat again without reloading it",
                                () -> {
                                    // The selection is cleared and put back on the chat that is
                                    // already open, which is how a list refresh re-selects it and the
                                    // sequence that used to count as opening it a second time.
                                    // clearAndSelect cannot stand in: it does nothing when the row
                                    // is already selected, so no listener would run.
                                    watchForLoadingPlaceholder(stage, LOADING_MESSAGES, reselectShowedLoading);
                                    assertThat(onFx(() -> reselectConversation(stage,
                                            FakeChatServer.FRIEND_NAME))).isTrue();
                                },
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && subtitleIs(stage, DIRECT_SUBTITLE)
                                        && showsExactly(stage, "mine to delete")
                                        && !reselectShowedLoading.get()),
                        new Step("delete a message for this user only",
                                () -> assertThat(onFx(() -> fireMessageMenu(stage, "mine to delete", "for me")))
                                        .as("the row offers its own menu: "
                                                + onFx(() -> diagnostics(stage)))
                                        .isTrue(),
                                () -> !showsExactly(stage, "mine to delete")),
                        new Step("decline delete for everyone at its confirmation",
                                () -> answerConfirmation(() -> assertThat(fireMessageMenu(stage,
                                        "mine to delete for everyone", "everyone")).isTrue(), false),
                                () -> server.deletedScopes().equals(List.of("me"))
                                        && showsExactly(stage, "mine to delete for everyone")
                                        && !mentions(stage, "This message was deleted.")),
                        new Step("delete for everyone through its confirmation",
                                () -> confirmThen(() -> assertThat(fireMessageMenu(stage,
                                        "mine to delete for everyone", "everyone")).isTrue()),
                                () -> server.deletedScopes().contains("everyone")
                                        && mentions(stage, "This message was deleted.")),
                        new Step("decline clearing the history at its confirmation",
                                () -> answerConfirmation(() -> {
                                    Button clear = button(stage, "Clear history");
                                    assertThat(clear).as("the direct chat offers clear history").isNotNull();
                                    clear.fire();
                                }, false),
                                () -> server.clearedHistories() == 0
                                        && showsExactly(stage, "from bob")
                                        && showsExactly(stage, "hello again")),
                        new Step("clear the history through its confirmation",
                                () -> confirmThen(() -> {
                                    Button clear = button(stage, "Clear history");
                                    assertThat(clear).as("the direct chat offers clear history").isNotNull();
                                    clear.fire();
                                }),
                                () -> server.clearedHistories() == 1
                                        && !showsExactly(stage, "hello again")
                                        && !showsExactly(stage, "from bob")),
                        new Step("open a chat from the friends dialog",
                                () -> {
                                    Platform.runLater(() -> button(stage, "Friends").fire());
                                    Window dialog = awaitDialog("#userList");
                                    Platform.runLater(() -> primaryButton(dialog).fire());
                                },
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && subtitleIs(stage, DIRECT_SUBTITLE)),
                        new Step("create a group from the picker",
                                () -> {
                                    Platform.runLater(() -> button(stage, "New group").fire());
                                    Window dialog = awaitDialog("#userList");
                                    Platform.runLater(() -> {
                                        ((TextField) dialog.getScene().lookup("#nameField"))
                                                .setText(CREATED_GROUP);
                                        ((ListView<?>) dialog.getScene().lookup("#userList"))
                                                .getSelectionModel().select(0);
                                        primaryButton(dialog).fire();
                                    });
                                },
                                () -> listsConversation(stage, CREATED_GROUP)
                                        && titleIs(stage, FakeChatServer.FRIEND_NAME)),
                        new Step("a realtime event for the open chat",
                                () -> sendCreated(realtime, FakeChatServer.CONVERSATION_ID, "live from bob"),
                                () -> showsExactly(stage, "live from bob")),
                        new Step("a realtime event for the group that is no longer open",
                                () -> sendCreated(realtime, FakeChatServer.GROUP_ID, "late group message"),
                                // The stale event repainted nothing, and the shell is still the one
                                // the user had.
                                () -> !mentions(stage, "late group message")
                                        && titleIs(stage, FakeChatServer.FRIEND_NAME)),
                        new Step("type a query nothing in the cache answers yet",
                                () -> onFx(() -> {
                                    searchField(stage).setText(SEARCH_QUERY);
                                    return null;
                                }),
                                () -> searchVisible(stage) && searchPreviews(stage).isEmpty()),
                        new Step("a message that matches is cached and the results pick it up",
                                // A group message while the direct chat is open: the cache moves and
                                // the panel has to answer with it, not with what it held before.
                                () -> sendCreated(realtime, FakeChatServer.GROUP_ID, SEARCHED_MESSAGE),
                                () -> searchPreviews(stage).contains(SEARCHED_MESSAGE)),
                        new Step("clear the query and the panel goes away",
                                () -> onFx(() -> {
                                    searchField(stage).clear();
                                    return null;
                                }),
                                () -> !searchVisible(stage) && searchPreviews(stage).isEmpty()),
                        new Step("a refresh whose list no longer has the open chat",
                                () -> {
                                    // The only refresh an idle shell runs comes from an MLS event, and
                                    // this row wants the open chat to be the thing that went away, so
                                    // the direct chat leaves the server's list first.
                                    server.hideDirectConversation();
                                    sendMlsEvent(realtime, FakeChatServer.GROUP_ID);
                                },
                                () -> emptyShell(stage)),
                        new Step("leave the open group and land on the empty shell",
                                () -> {
                                    // The empty state is what is left once nothing can be opened, so
                                    // the direct chat goes first: the refresh that follows the leave
                                    // then finds no conversation at all.
                                    server.hideDirectConversation();
                                    assertThat(onFx(() -> selectConversation(stage, CREATED_GROUP))).isTrue();
                                    Platform.runLater(() -> button(stage, "Group settings").fire());
                                    Window settings = awaitDialog("#nameField");
                                    Platform.runLater(() -> button(settings.getScene(), "Leave group").fire());
                                    answerConfirmButton("#confirmDialogConfirm");
                                },
                                () -> server.removedMembers().contains(FakeChatServer.USER_ID)
                                        && emptyShell(stage)),
                        new Step("open the direct chat again from Friends",
                                () -> {
                                    // The leave left the account without conversations, so the list
                                    // has to carry this chat again before one can be open while the
                                    // list still has it.
                                    server.showDirectConversation();
                                    Platform.runLater(() -> button(stage, "Friends").fire());
                                    Window dialog = awaitDialog("#userList");
                                    Platform.runLater(() -> primaryButton(dialog).fire());
                                },
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && subtitleIs(stage, DIRECT_SUBTITLE)),
                        new Step("a conversation-list load that comes back empty while a chat is open",
                                () -> {
                                    // Creating a group is the one action that ends in a plain list
                                    // load rather than a refresh, and the fake serves no
                                    // conversations once the chat is gone, which is the stale read a
                                    // shell has to survive: the open chat goes with the list.
                                    server.hideDirectConversation();
                                    Platform.runLater(() -> button(stage, "New group").fire());
                                    Window dialog = awaitDialog("#userList");
                                    Platform.runLater(() -> {
                                        ((TextField) dialog.getScene().lookup("#nameField"))
                                                .setText("Third team");
                                        ((ListView<?>) dialog.getScene().lookup("#userList"))
                                                .getSelectionModel().select(0);
                                        primaryButton(dialog).fire();
                                    });
                                },
                                () -> server.groupsCreated() == 2
                                        && emptyShell(stage)),
                        new Step("rejoin the group the account had left",
                                () -> {
                                    // The leave dropped the server membership, so the group the last
                                    // row created is not in the list; the account is a member again
                                    // here, the way a fresh invitation would make it.
                                    server.restoreMembership();
                                    server.showCarolFriend();
                                    sendMlsEvent(realtime, FakeChatServer.GROUP_ID);
                                },
                                () -> listsConversation(stage, THIRD_GROUP)),
                        new Step("open the group again",
                                () -> assertThat(onFx(() -> selectConversation(stage, THIRD_GROUP))).isTrue(),
                                () -> titleIs(stage, THIRD_GROUP) && subtitleIs(stage, GROUP_SUBTITLE)),
                        new Step("add a friend who is not a member yet",
                                () -> {
                                    Platform.runLater(() -> button(stage, "Add member").fire());
                                    Window dialog = awaitDialog("#userList");
                                    Platform.runLater(() -> {
                                        ((ListView<?>) dialog.getScene().lookup("#userList"))
                                                .getSelectionModel().select(0);
                                        primaryButton(dialog).fire();
                                    });
                                },
                                () -> server.addedMembers().contains(FakeChatServer.CAROL_ID)
                                        && titleIs(stage, THIRD_GROUP)),
                        new Step("remove a member from the open group",
                                () -> {
                                    Platform.runLater(() -> button(stage, "Remove member").fire());
                                    Window dialog = awaitDialog("#userList");
                                    Platform.runLater(() -> {
                                        ((ListView<?>) dialog.getScene().lookup("#userList"))
                                                .getSelectionModel().select(0);
                                        primaryButton(dialog).fire();
                                    });
                                },
                                () -> server.removedMembers().contains(FakeChatServer.FRIEND_ID)
                                        && titleIs(stage, THIRD_GROUP)),
                        new Step("a second direct chat joins the list",
                                () -> {
                                    server.showDirectConversation();
                                    server.showSecondDirectChat();
                                    server.addCarolMessage(CAROL_MESSAGE);
                                    sendMlsEvent(realtime, FakeChatServer.GROUP_ID);
                                },
                                () -> listsConversation(stage, FakeChatServer.FRIEND_NAME)
                                        && listsConversation(stage, FakeChatServer.CAROL_NAME)),
                        new Step("open the other direct chat so its message is cached",
                                () -> assertThat(onFx(() -> selectConversation(stage,
                                        FakeChatServer.CAROL_NAME))).isTrue(),
                                () -> titleIs(stage, FakeChatServer.CAROL_NAME)
                                        && showsExactly(stage, CAROL_MESSAGE)),
                        new Step("go back to the first direct chat",
                                () -> assertThat(onFx(() -> selectConversation(stage,
                                        FakeChatServer.FRIEND_NAME))).isTrue(),
                                () -> titleIs(stage, FakeChatServer.FRIEND_NAME)
                                        && !showsExactly(stage, CAROL_MESSAGE)),
                        new Step("search a message whose chat is not the open one",
                                () -> onFx(() -> {
                                    searchField(stage).setText("marigold");
                                    return null;
                                }),
                                // The hit belongs to another direct chat, so its sender name has to
                                // come from the list instead of the chat that is open.
                                () -> searchPreviews(stage).contains(CAROL_MESSAGE)),
                        new Step("clear the search before reporting",
                                () -> onFx(() -> {
                                    searchField(stage).clear();
                                    return null;
                                }),
                                () -> !searchVisible(stage)),
                        new Step("a message from the other side arrives for the report",
                                () -> sendCreated(realtime, FakeChatServer.CONVERSATION_ID,
                                        "reported wording"),
                                () -> showsExactly(stage, "reported wording")),
                        new Step("report the open direct chat with that message as evidence",
                                () -> {
                                    Platform.runLater(() -> {
                                        if (stage.getScene().lookup("#reportButton") instanceof Button report) {
                                            report.fire();
                                        }
                                    });
                                    Window report = awaitDialog("#reasonField");
                                    Platform.runLater(() -> {
                                        ((TextArea) report.getScene().lookup("#reasonField"))
                                                .setText("harassment");
                                        ((ListView<?>) report.getScene().lookup("#evidenceList"))
                                                .getSelectionModel().select(0);
                                        ((CheckBox) report.getScene().lookup("#consentCheck"))
                                                .setSelected(true);
                                        ((Button) report.getScene().lookup("#submitButton")).fire();
                                    });
                                },
                                () -> server.reports() == 1 && server.reportsWithEvidence() == 1));

                for (Step step : table) {
                    try {
                        step.action().run();
                    } catch (RuntimeException | AssertionError failed) {
                        throw new AssertionError("step \"" + step.state() + "\" failed while acting: "
                                + failed.getMessage() + " (shell: " + shellDiagnostics(stage) + ")",
                                failed);
                    }
                    try {
                        waitForFx(step.settled(), Duration.ofSeconds(10));
                    } catch (AssertionError unmet) {
                        throw new AssertionError("step \"" + step.state() + "\" did not settle: "
                                + unmet.getMessage() + " (shell: " + shellDiagnostics(stage) + ")",
                                unmet);
                    }
                }

                assertThat(server.deletedScopes())
                        .as("both delete scopes went through the row menu's own actions")
                        .containsExactly("me", "everyone");
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

    /** Answers a confirmation the way most rows do: accept it. */
    private static void confirmThen(Runnable open) {
        answerConfirmation(open, true);
    }

    /**
     * Opens a modal confirmation from the test thread and answers it, waiting for the dialog to appear
     * and for it to go away — a click that never lands would otherwise leave the FX thread parked in
     * {@code showAndWait} and fail the next row with a timeout that names nothing.
     */
    private static void answerConfirmation(Runnable open, boolean accept) {
        Platform.runLater(open);
        answerConfirmButton(accept ? "#confirmDialogConfirm" : "#confirmDialogCancel");
    }

    /** Answers the confirmation a step's own action opened, once it is on screen. */
    private static void answerConfirmButton(String id) {
        waitForFx(() -> confirmButton(id) != null, Duration.ofSeconds(10));
        Platform.runLater(() -> {
            Node answer = confirmButton(id);
            if (answer instanceof Button button) button.fire();
        });
        waitForFx(() -> confirmButton(id) == null, Duration.ofSeconds(10));
    }

    /** The picker dialog's own window, once the asynchronous friend list has filled it. */
    private static Window awaitDialog(String lookupId) {
        waitForFx(() -> dialogShowing(lookupId), Duration.ofSeconds(10));
        Window dialog = null;
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null && window.getScene().lookup(lookupId) != null) {
                dialog = window;
            }
        }
        if (dialog == null) throw new AssertionError("The dialog with " + lookupId + " closed before it was used");
        return dialog;
    }

    private static boolean dialogShowing(String lookupId) {
        return Window.getWindows().stream().anyMatch(window -> window.isShowing()
                && window.getScene() != null && window.getScene().lookup(lookupId) != null);
    }

    /**
     * Renames through the settings dialog once its rename button is enabled. The controller learns
     * that the signed-in user is an admin from the member list and keeps the action disabled until
     * then, so this waits for that state — the same readiness a person sees — instead of clicking a
     * button whose action the dialog would refuse.
     */
    private static void renameWhenReady(Window settings, String name) {
        waitForFx(() -> settings.getScene().lookup("#renameButton") instanceof Button rename
                && !rename.isDisabled(), Duration.ofSeconds(10));
        Platform.runLater(() -> {
            ((TextField) settings.getScene().lookup("#nameField")).setText(name);
            primaryButton(settings).fire();
        });
    }

    /** A picker's confirm button is the styled primary one; its cancel is the ghost next to it. */
    private static Button primaryButton(Window dialog) {
        if (dialog.getScene().lookup(".primary-button") instanceof Button confirm) return confirm;
        throw new AssertionError("The dialog has no primary button");
    }

    /** The shell with nothing open: the empty-state copy and the actions that need a chat. */
    private static boolean emptyShell(Stage stage) {
        return titleIs(stage, NO_CONVERSATION_TITLE)
                && subtitleIs(stage, NO_CONVERSATION_HINT)
                && disabled(stage, "#reportButton")
                && disabled(stage, "#groupSettingsButton")
                && disabled(stage, "#clearHistoryButton");
    }

    /** Whether the sidebar is listing a conversation under this name. */
    private static boolean listsConversation(Stage stage, String name) {
        if (stage.getScene() == null
                || !(stage.getScene().lookup("#conversationList") instanceof ListView<?> list)) {
            return false;
        }
        return list.getItems().stream()
                .filter(Conversation.class::isInstance)
                .map(Conversation.class::cast)
                .anyMatch(conversation -> name.equals(conversation.getName()));
    }

    private static boolean disabled(Stage stage, String id) {
        return stage.getScene().lookup(id) instanceof Node node && node.isDisabled();
    }

    /** The dialog's own buttons live in a second window, not in the shell's scene. */
    private static Node confirmButton(String id) {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) continue;
            Node button = window.getScene().lookup(id);
            if (button != null) return button;
        }
        return null;
    }

    /** A group event, which is what makes the shell re-read its conversation list. */
    private static void sendMlsEvent(FakeWebSocketServer realtime, UUID conversation) {
        try {
            realtime.sendText("{\"type\":\"MlsMessage\",\"conversationId\":\"" + conversation + "\"}");
        } catch (java.io.IOException failure) {
            throw new AssertionError("The test could not send a realtime event", failure);
        }
    }

    private static void sendCreated(FakeWebSocketServer realtime, UUID conversation, String content) {
        try {
            realtime.sendText("{\"type\":\"MessageCreated\",\"conversationId\":\"" + conversation
                    + "\",\"message\":{\"id\":\"" + UUID.randomUUID()
                    + "\",\"conversationId\":\"" + conversation
                    + "\",\"senderId\":\"" + FakeChatServer.FRIEND_ID
                    + "\",\"content\":\"" + content + "\","
                    + "\"createdAt\":\"2026-11-14T10:00:00Z\"}}");
        } catch (java.io.IOException failure) {
            throw new AssertionError("The test could not send a realtime event", failure);
        }
    }

    /**
     * Fires the action whose text contains {@code itemText} on the row showing {@code text}: a menu
     * looked up by position would be the conversation list's own menu, which deletes a chat instead.
     */
    private static boolean fireMessageMenu(Stage stage, String text, String itemText) {
        MenuItem item = messageMenuItem(stage, text, itemText);
        if (item == null) return false;
        item.fire();
        return true;
    }

    /** Whether the action {@link #fireMessageMenu} would find is available for that row right now. */
    private static boolean rowMenuOffers(Stage stage, String text, String itemText) {
        return messageMenuItem(stage, text, itemText) != null;
    }

    private static MenuItem messageMenuItem(Stage stage, String text, String itemText) {
        Scene scene = stage.getScene();
        if (scene == null) return null;
        for (Node node : scene.getRoot().lookupAll(".menu-button")) {
            if (!(node instanceof MenuButton menu) || menu.getItems().isEmpty()) continue;
            if (!rowShows(menu, text)) continue;
            for (MenuItem item : menu.getItems()) {
                if (item.getText() != null && item.getText().toLowerCase().contains(itemText)) return item;
            }
        }
        return null;
    }

    /** True when the message row around {@code menu}, and no other row, shows exactly {@code text}. */
    private static boolean rowShows(Node menu, String text) {
        Node row = menu.getParent();
        while (row != null && !row.getStyleClass().contains("message-row")) row = row.getParent();
        return row != null && labelsIn(row).contains(text);
    }

    /** Enough of the shell to tell a repaint that never happened from one that painted the wrong thing. */
    private static String diagnostics(Stage stage) {
        return titleOf(stage) + ", status "
                + (stage.getScene().lookup("#statusLabel") instanceof Label status ? status.getText() : null)
                + ", rows " + rowsOf(stage);
    }

    /**
     * The shell's state for a failure report, or why it could not be read. A run that fails because
     * the FX thread is stuck cannot answer a diagnostic read either, and losing the failed step to a
     * second timeout is worse than reporting the step without the shell's labels.
     */
    private static String shellDiagnostics(Stage stage) {
        long start = System.nanoTime();
        try {
            String state = onFx(() -> diagnostics(stage));
            return state + ", FX thread answered in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms";
        } catch (AssertionError fxThreadStuck) {
            return "unavailable, the FX thread did not answer: " + fxThreadStuck.getMessage();
        }
    }

    private static String rowsOf(Stage stage) {
        if (!(stage.getScene().lookup("#messageList") instanceof ListView<?> list)) return "none";
        StringBuilder text = new StringBuilder("[");
        for (Object item : list.getItems()) text.append(item).append(", ");
        return text.append(']').toString();
    }

    private static boolean statusIs(Stage stage, String text) {
        return text.equals(statusOf(stage));
    }

    private static String statusOf(Stage stage) {
        return stage.getScene().lookup("#statusLabel") instanceof Label status ? status.getText() : null;
    }

    private static boolean titleIs(Stage stage, String title) {
        return title.equals(titleOf(stage));
    }

    /**
     * Records whether the timeline is ever swapped to its loading placeholder: the children are watched
     * rather than sampled, so a swap that is undone before the next poll still counts.
     */
    private static void watchForLoadingPlaceholder(Stage stage, String loadingText, AtomicBoolean seen) {
        if (!(stage.getScene().lookup("#messageList") instanceof ListView<?> list)) {
            throw new AssertionError("The shell has no timeline");
        }
        Consumer<Node> inspect = placeholder -> {
            if (placeholder != null && labelsIn(placeholder).contains(loadingText)) seen.set(true);
        };
        // Only a change counts. A placeholder already on screen when the watch is installed was put
        // there by whatever the timeline was doing before this step, not by the refresh the step is
        // about; recording it would fail the step for a state the refresh did not cause.
        list.placeholderProperty().addListener((observable, previous, next) -> inspect.accept(next));
    }

    private static boolean subtitleIs(Stage stage, String text) {
        return text.equals(subtitleOf(stage));
    }

    private static String subtitleOf(Stage stage) {
        return stage.getScene() == null ? null
                : stage.getScene().lookup("#chatSubtitleLabel") instanceof Label label ? label.getText() : null;
    }

    private static String titleOf(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return null;
        return scene.getRoot().lookupAll(".chat-title").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .map(Label::getText).filter(text -> text != null)
                .findFirst().orElse(null);
    }

    /** Clears the selection and re-selects {@code name}, the way a refresh re-selects open chat. */
    private static boolean reselectConversation(Stage stage, String name) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        for (Node node : scene.getRoot().lookupAll(".list-view")) {
            if (!(node instanceof ListView<?> list)) continue;
            for (int index = 0; index < list.getItems().size(); index++) {
                if (list.getItems().get(index) instanceof Conversation conversation
                        && name.equals(conversation.getName())) {
                    list.getSelectionModel().clearSelection();
                    list.getSelectionModel().select(index);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean selectConversation(Stage stage, String name) {
        Scene scene = stage.getScene();
        if (scene == null) return false;
        for (Node node : scene.getRoot().lookupAll(".list-view")) {
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

    /** Every label text under {@code node}: the one traversal the shell's assertions are built on. */
    private static List<String> labelsIn(Node node) {
        return node.lookupAll(".label").stream().filter(Label.class::isInstance)
                .map(Label.class::cast).map(Label::getText)
                .filter(text -> text != null).toList();
    }

    /** A label whose whole text is {@code text}; exact, because one message's text prefixes another's. */
    private static boolean showsExactly(Stage stage, String text) {
        return stage.getScene() != null && labelsIn(stage.getScene().getRoot()).contains(text);
    }

    private static boolean mentions(Stage stage, String text) {
        return stage.getScene() != null && labelsIn(stage.getScene().getRoot()).stream()
                .anyMatch(label -> label.contains(text));
    }

    private static TextField searchField(Stage stage) {
        if (stage.getScene() != null && stage.getScene().lookup("#searchField") instanceof TextField box) {
            return box;
        }
        throw new AssertionError("The shell has no search box");
    }

    private static boolean searchVisible(Stage stage) {
        Node results = stage.getScene() == null ? null : stage.getScene().lookup("#searchResults");
        return results != null && results.isVisible();
    }

    /** What the search panel is listing, by preview: the one field a result carries from the cache. */
    private static List<String> searchPreviews(Stage stage) {
        if (stage.getScene() == null
                || !(stage.getScene().lookup("#searchResults") instanceof ListView<?> results)) {
            return List.of();
        }
        return results.getItems().stream()
                .filter(MessageSearchResult.class::isInstance)
                .map(MessageSearchResult.class::cast)
                .map(MessageSearchResult::preview)
                .toList();
    }

    private static TextField messageField(Stage stage) {
        if (stage.getScene().lookup("#composeField") instanceof TextField box) return box;
        throw new AssertionError("The shell has no message box");
    }

    private static Button button(Stage stage, String text) {
        return stage.getScene() == null ? null : button(stage.getScene(), text);
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst().orElse(null);
    }

    private static TextField field(Scene scene, String prompt) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(candidate -> prompt.equals(candidate.getPromptText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing JavaFX field: " + prompt));
    }
}
