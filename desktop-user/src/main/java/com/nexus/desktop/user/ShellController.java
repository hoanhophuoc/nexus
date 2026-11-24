package com.nexus.desktop.user;

import com.nexus.desktop.shared.cache.CachedMessage;
import com.nexus.desktop.shared.cache.MessageSearchResult;
import com.nexus.desktop.shared.cache.PendingMessage;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.desktop.shared.ui.ConfirmDialog;
import com.nexus.desktop.shared.ui.Icons;
import com.nexus.desktop.shared.ui.UiComponents;
import com.nexus.sdk.ApiException;
import com.nexus.sdk.model.Conversation;
import com.nexus.sdk.model.CreateDirectConversationRequest;
import com.nexus.sdk.model.GroupMember;
import com.nexus.sdk.model.MyProfile;
import com.nexus.sdk.model.User;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
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
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** FXML controller for the signed-in user shell. */
public final class ShellController {
    /** Keys that move the viewport; pressing one ends the follow intent, like a wheel scroll does. */
    private static final Set<KeyCode> SCROLL_KEYS = EnumSet.of(
            KeyCode.UP, KeyCode.DOWN, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN, KeyCode.HOME, KeyCode.END);
    /**
     * How many times a viewport move may re-apply itself while the list measures the rows it just
     * gained. Counted in passes, not in wall time: what the move waits for is the layout the list
     * still has to do, and a loaded machine stretches that layout without changing it, so a time
     * budget makes the shell give up mid-move on a slow runner and never land on the row.
     */
    private static final int VIEWPORT_PASS_LIMIT = 60;

    private final UserAppContext context;
    private final I18n i18n;
    private final Runnable onSignedOut;
    private final DateTimeFormatter messageTime;
    private final DateTimeFormatter dayFormat;
    /** The rows the list renders; only the ones it shows become scene-graph nodes. */
    private final ObservableList<TimelineItem> timelineItems = FXCollections.observableArrayList();
    /** Ids in the order the rows show them, which is what makes an append-only update detectable. */
    private final List<UUID> renderedMessageIds = new ArrayList<>();
    private int renderedPendingCount;
    private LocalDate lastRenderedDay;
    /** The search hit to mark; a cell reads it, so recycling a cell cannot lose it. */
    private UUID revealedMessageId;
    /** True while the timeline is meant to show the newest row: recomputed on every repaint. */
    private boolean followTail;
    /** True while a follow is still re-applying itself, so an arrival there counts as at the bottom. */
    private boolean followInFlight;
    /** Bumped whenever the reader takes the viewport over, so a move in flight stops fighting them. */
    private int viewportMoveGeneration;
    private Conversation activeConversation;
    private final OpenGeneration openGeneration = new OpenGeneration();

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
    @FXML private ListView<TimelineItem> messageList;
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
                    // A list refresh replaces the items and re-selects the chat that is already open,
                    // which clears the selection first. That chat keeps its timeline — reopening it
                    // would blank the rows to their loading state and re-sync for a chat the user
                    // never left — but its header still moves, so a renamed chat does not go stale.
                    if (selected == null) return;
                    if (activeConversation != null
                            && selected.getId().equals(activeConversation.getId())) {
                        showConversationHeader(selected);
                    } else {
                        openConversation(selected, null);
                    }
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

        messageList.setItems(timelineItems);
        messageList.setCellFactory(ignored -> new TimelineCell());
        messageList.setPlaceholder(UiComponents.emptyState(
                i18n.get("shell.noMessages"), i18n.get("shell.noMessagesHint"), null, null));

        // A reader who scrolls away from the newest row is no longer following it. A wheel scroll,
        // a pressed scrollbar thumb or track and a keyboard move are all started by the reader, so
        // each clears the same intent; a programmatic scroll fires none of them.
        messageList.addEventFilter(ScrollEvent.SCROLL, event -> stopFollowing());
        messageList.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> stopFollowing());
        messageList.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!SCROLL_KEYS.contains(event.getCode())) return;
            stopFollowing();
            scrollByKey(event.getCode());
            event.consume();
        });
        // A narrower window re-wraps every visible cell, so a reader at the newest row stays there.
        messageList.widthProperty().addListener((observable, previous, next) -> {
            if (followTail) scrollToLatestAfterLayout();
        });

        statusDot.getStyleClass().setAll("presence-dot", "offline");
        updateThemeButton();
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
        // The open in progress when the fetch starts, so a chat opened while it was in flight is not
        // mistaken for one the server no longer has.
        int generation = openGeneration.current();
        context.runAsync(this::listConversations, all -> {
            // List replies cannot overtake one another: the shell asks for the list on a single
            // serial executor, so the reply that lands last is always the newest request's.
            // The selection goes first: replacing the items under a selection for a chat the list no
            // longer has would hand it to the neighbour, which reads as the user having opened that
            // chat, and a refresh must never open one the user did not pick.
            conversationList.getSelectionModel().clearSelection();
            conversationList.setItems(FXCollections.observableArrayList(all));
            // Read at completion, not at start: a refresh that was already in flight when the user
            // opened another chat would otherwise put the old one back and take the title with it.
            UUID open = activeConversation == null ? null : activeConversation.getId();
            if (open == null) return;
            Conversation refreshed = all.stream()
                    .filter(conversation -> conversation.getId().equals(open))
                    .findFirst().orElse(null);
            if (refreshed != null) {
                conversationList.getSelectionModel().select(refreshed);
            } else if (openGeneration.isCurrent(generation)) {
                // The chat that was open is not one of the account's conversations any more, so there
                // is nothing left to show: the shell clears with the list instead of keeping the
                // header and the actions of a chat the server has stopped having.
                showEmptyChat();
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
        showConversationHeader(conversation);
        int open = openGeneration.open();
        resetTimelineState();
        timelineItems.clear();
        messageList.setPlaceholder(UiComponents.loadingState(i18n.get("shell.loadingMessages")));
        context.runAsync(() -> {
            if (conversation.getType() == Conversation.TypeEnum.GROUP) {
                context.groups().syncGroup(conversation.getId());
            } else {
                context.messaging().syncConversation(conversation.getId());
            }
            return null;
        }, ignored -> refreshIfStillOpen(open, messageToReveal), exception -> {
            showStatusError(exception);
            resetTimelineState();
            timelineItems.clear();
            messageList.setPlaceholder(UiComponents.errorState(
                    i18n.get("error.title"),
                    UserErrors.statusMessage(exception, i18n),
                    i18n.get("shell.retry"),
                    () -> openConversation(conversation, messageToReveal)));
        });
    }

    /**
     * Everything the shell says about the open chat: its title, its subtitle and the buttons only one
     * kind of chat offers. Separate from opening it, because a list refresh can re-select the chat that
     * is already open and only its header needs to follow the refreshed name.
     */
    private void showConversationHeader(Conversation conversation) {
        activeConversation = conversation;
        boolean group = conversation.getType() == Conversation.TypeEnum.GROUP;
        reportButton.setDisable(group);
        groupSettingsButton.setDisable(!group);
        clearHistoryButton.setDisable(group);
        // A direct chat has no membership to edit; the buttons say so instead of answering with a
        // status line that a reader has to go and find.
        addMemberButton.setDisable(!group);
        removeMemberButton.setDisable(!group);
        String title = conversation.getName() == null || conversation.getName().isBlank()
                ? i18n.get("shell.directChat") : conversation.getName();
        chatTitleLabel.setText(title);
        chatSubtitleLabel.setText(group
                ? i18n.get("shell.groupSubtitle") : i18n.get("shell.directSubtitle"));
    }

    /** A completion started for one open must not repaint a conversation opened since. */
    private void refreshIfStillOpen(int open, UUID messageToReveal) {
        // The completion belongs to something the user did in this chat — opening it, sending,
        // deleting or clearing — so the timeline follows its newest row.
        if (openGeneration.isCurrent(open)) refreshVisibleMessages(messageToReveal, true);
    }

    /** Repaints after activity the shell received rather than caused: the reader's place is kept. */
    private void refreshVisibleMessages(UUID messageToReveal) {
        refreshVisibleMessages(messageToReveal, false);
    }

    /**
     * Repaints the open timeline.
     *
     * <p>{@code followLatest} says who moved it: a chat the user opened or a message the user sent
     * goes to the newest row, while activity the shell merely received leaves the reader where they
     * were. A timeline that only grew gets its new rows appended, which cannot move the rows above
     * them; a rebuild restores the message the reader was looking at, by id and by how far that
     * message sat below the top of the viewport.
     */
    private void refreshVisibleMessages(UUID messageToReveal, boolean followLatest) {
        Conversation current = activeConversation;
        if (current == null || context.cache() == null) return;
        List<CachedMessage> stored = context.cache().messagesInConversation(current.getId());
        List<PendingMessage> pending = context.cache().pendingInConversation(current.getId());
        // A follow this shell started counts as the reader still being at the bottom while it runs: an
        // arrival lands between the passes of a move that is still re-applying, and reading the scroll
        // bar mid-move would say the reader left and stop following for good. Once the move lands or
        // gives up, the scroll bar is the truth again.
        boolean wasAtBottom = isTimelineAtBottom() || followInFlight;
        TimelineAnchor anchor = messageToReveal == null && !followLatest
                ? captureTimelineAnchor() : null;
        boolean follow = followLatest || wasAtBottom || anchor == null;
        followTail = follow && messageToReveal == null;
        if (messageToReveal == null && !followLatest && appendNewMessages(stored, pending)) {
            if (followTail) scrollToLatestAfterLayout();
            refreshSearch(searchField.getText());
            return;
        }
        renderTimeline(stored, pending);
        if (messageToReveal != null) {
            revealMessage(messageToReveal);
        } else if (followTail) {
            scrollToLatestAfterLayout();
        } else {
            restoreTimelineAnchor(anchor);
        }
        refreshSearch(searchField.getText());
    }

    /**
     * Replaces the timeline content. The list builds a node only for the cells it shows and recycles
     * them as the reader scrolls, so the scene graph stays bounded however long the history is.
     */
    private void renderTimeline(List<CachedMessage> stored, List<PendingMessage> pending) {
        resetTimelineState();
        timelineItems.clear();
        if (stored.isEmpty() && pending.isEmpty()) {
            messageList.setPlaceholder(UiComponents.emptyState(
                    i18n.get("shell.noMessages"), i18n.get("shell.noMessagesHint"), null, null));
            return;
        }
        messageList.setPlaceholder(null);
        for (CachedMessage message : stored) appendStoredItem(message);
        for (PendingMessage pendingMessage : pending) {
            timelineItems.add(new TimelineItem.Pending(pendingMessage));
        }
        renderedPendingCount = pending.size();
    }

    /** Drops what the timeline remembers about its rows, so no later repaint appends to them. */
    private void resetTimelineState() {
        renderedMessageIds.clear();
        renderedPendingCount = 0;
        lastRenderedDay = null;
    }

    private void appendStoredItem(CachedMessage message) {
        LocalDate day = LocalDate.ofInstant(message.createdAt(), ZoneId.systemDefault());
        if (!day.equals(lastRenderedDay)) {
            timelineItems.add(new TimelineItem.Day(day));
            lastRenderedDay = day;
        }
        boolean mine = context.userId() != null && context.userId().equals(message.senderId());
        timelineItems.add(new TimelineItem.Bubble(message, mine,
                message.deletedForEveryoneAt() != null));
        renderedMessageIds.add(message.id());
    }

    /**
     * Appends the rows a delivery added, but only when the history grew at its end. A deletion, an
     * edit, a backfilled older message or a queued row reorders or rewrites rows, so each of those
     * falls back to a rebuild instead of guessing which rows moved.
     */
    private boolean appendNewMessages(List<CachedMessage> stored, List<PendingMessage> pending) {
        if (renderedMessageIds.isEmpty() || renderedPendingCount != 0 || !pending.isEmpty()) {
            return false;
        }
        if (stored.size() <= renderedMessageIds.size()) return false;
        for (int index = 0; index < renderedMessageIds.size(); index++) {
            if (!renderedMessageIds.get(index).equals(stored.get(index).id())) return false;
        }
        for (int index = renderedMessageIds.size(); index < stored.size(); index++) {
            appendStoredItem(stored.get(index));
        }
        return true;
    }

    /**
     * The message the reader was at when the timeline was rebuilt, by id only.
     *
     * <p>It deliberately does not carry the message's pixel offset inside the viewport. A
     * {@code ListView} addresses its content through {@code scrollTo(int)}, which places a row at the
     * top of the viewport; the pixels between rows belong to the internal {@code VirtualFlow}, which
     * is not a supported extension point and is therefore not used. Restoring the exact offset would
     * mean measuring and re-driving that flow, which is the coupling this timeline avoids to stay
     * virtualized.
     *
     * <p>The consequence is precise and accepted: after a rebuild the anchored message is at the top
     * of the viewport, so the reader keeps the message they were reading, but if that row was taller
     * than the remaining space and had already scrolled partly above the top edge, the part above
     * the edge is scrolled back into view. {@code ChatTimelineScrollFxTest} asserts this with a long
     * wrapped row. Appends never rebuild and therefore never move the anchor at all.
     */
    private record TimelineAnchor(UUID messageId) { }

    /**
     * The message at the top of the viewport.
     *
     * <p>{@code lookupAll} returns its cells in no particular order, so the first bubble in that set
     * is an arbitrary visible message rather than the topmost one; anchoring on it would restore the
     * reader to a message they were not reading. The lowest index is the row to keep.
     */
    private TimelineAnchor captureTimelineAnchor() {
        int top = Integer.MAX_VALUE;
        UUID anchorId = null;
        for (Node cell : messageList.lookupAll(".timeline-cell")) {
            if (!(cell instanceof ListCell<?> listCell) || listCell.isEmpty()) continue;
            Bounds bounds = messageList.sceneToLocal(cell.localToScene(cell.getBoundsInLocal()));
            if (bounds.getMaxY() <= 0) continue;
            if (!(listCell.getItem() instanceof TimelineItem.Bubble bubble)) continue;
            if (listCell.getIndex() < top) {
                top = listCell.getIndex();
                anchorId = bubble.message().id();
            }
        }
        return anchorId == null ? null : new TimelineAnchor(anchorId);
    }

    private void restoreTimelineAnchor(TimelineAnchor anchor) {
        if (anchor == null) return;
        int index = indexOfMessage(anchor.messageId());
        if (index < 0) return;
        scrollToRowAfterLayout(index, viewportMoveGeneration, VIEWPORT_PASS_LIMIT);
    }

    /**
     * Puts a row at the top of the viewport, re-applying the move while the list measures the rows
     * it was just rebuilt with. A scroll that runs before that pass computes its target from the
     * previous rows, lands on the wrong one, and a single call is never retried: the reader ends up
     * beside the message they were reading. The move stops as soon as the row is at the top, when
     * the list is already as far down as it goes, when the reader takes the viewport over, or after
     * {@link #VIEWPORT_PASS_LIMIT} passes. "As far down as it goes" is only trusted once the list has
     * a measured scroll bar: a list that was just rebuilt has none yet, and {@link #isTimelineAtBottom()}
     * reads that as the end of a history that fits.
     */
    private void scrollToRowAfterLayout(int index, int generation, int passesLeft) {
        if (generation != viewportMoveGeneration || passesLeft <= 0) return;
        messageList.scrollTo(index);
        if (firstVisibleRow() >= index || (timelineIsMeasured() && isTimelineAtBottom())) return;
        Platform.runLater(() -> {
            measureTimeline();
            scrollToRowAfterLayout(index, generation, passesLeft - 1);
        });
    }

    /** Brings a revealed message into view; the list addresses rows by index, not by fraction. */
    private void revealMessage(UUID messageId) {
        int index = indexOfMessage(messageId);
        if (index < 0) return;
        revealedMessageId = messageId;
        // Revealing is the opposite of following: the reader asked for this row, not the newest one.
        stopFollowing();
        // A little room above the hit, so the match is not stuck against the header.
        messageList.scrollTo(Math.max(0, index - 1));
        messageList.refresh();
    }

    private int indexOfMessage(UUID messageId) {
        for (int index = 0; index < timelineItems.size(); index++) {
            if (timelineItems.get(index) instanceof TimelineItem.Bubble bubble
                    && bubble.message().id().equals(messageId)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Lands on the newest row. A list does not move when an item is appended, so the follower is
     * re-sent to the tail rather than relying on the content height to drag the viewport along.
     */
    private void scrollToLatestAfterLayout() {
        followTail = true;
        followInFlight = true;
        followToLatest(VIEWPORT_PASS_LIMIT);
    }

    /** The reader took over: no follow runs and none is treated as in flight. */
    private void stopFollowing() {
        followTail = false;
        followInFlight = false;
        viewportMoveGeneration++;
    }

    /**
     * Sends the viewport to the newest row, re-applying the move while the list catches up.
     *
     * <p>A list that has just gained rows measures them on its next layout pass. A scroll that runs
     * before that pass computes its target from the previous content extent and stops short of the
     * new row, so the move is repeated until the newest row is really inside the viewport. It stops
     * on its own as soon as it lands, and gives up after {@link #VIEWPORT_PASS_LIMIT} passes rather
     * than polling forever if the row can never fit (a message taller than the viewport). The follow
     * flag is read again on every pulse: a reader who scrolled in between must not be dragged back.
     */
    private void followToLatest(int passesLeft) {
        if (!followTail || timelineItems.isEmpty() || passesLeft <= 0) {
            followInFlight = false;
            return;
        }
        messageList.scrollTo(timelineItems.size() - 1);
        if (landedOnNewestRow()) {
            followInFlight = false;
            return;
        }
        Platform.runLater(() -> {
            measureTimeline();
            followToLatest(passesLeft - 1);
        });
    }

    /**
     * True when the move to the newest row has really landed.
     *
     * <p>{@link #isTimelineAtBottom()} answers the reader's question and reads a list with no scroll
     * bar as "everything fits", which is right for a short history. A list that has just been rebuilt
     * also has no scroll bar and almost no cells yet, so that answer would end the move before the
     * list measured the rows it was given; the next repaint then finds the viewport at the top, reads
     * it as the reader having left, and stops following for good. Landing therefore also requires the
     * newest row to be the rendered last row, and the move keeps re-applying until it is.
     */
    private boolean landedOnNewestRow() {
        if (!isTimelineAtBottom()) return false;
        if (timelineItems.isEmpty()) return true;
        int newest = timelineItems.size() - 1;
        for (Node node : messageList.lookupAll(".timeline-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getIndex() == newest) {
                return true;
            }
        }
        return false;
    }

    /**
     * Measures the rows the list has just been given. The rows a scroll addresses are placed by a
     * layout pass, so a move that only queues another scroll is racing pulses it does not control: on
     * a loaded machine the queued passes can all run between two passes and give up on a viewport
     * that never had the chance to grow. Asking for the pass is the same rule the rest of the shell
     * follows — measure what you are about to decide on.
     */
    private void measureTimeline() {
        messageList.applyCss();
        messageList.layout();
    }

    /** True when the list has a scroll bar that measured its content, so "at the bottom" is real. */
    private boolean timelineIsMeasured() {
        for (Node node : messageList.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                return bar.getMax() > 0;
            }
        }
        return false;
    }

    /**
     * True when the newest row is on screen, the only state that may follow an arriving message.
     *
     * <p>The list's own scroll position is the statement to trust here: a recycled cell keeps the
     * bounds it last had, so reading a cell's place in the viewport can call a reader at the top
     * "at the bottom". The vertical scroll bar is read, never written: the skin owns that value.
     */
    private boolean isTimelineAtBottom() {
        if (timelineItems.isEmpty()) return true;
        for (Node node : messageList.lookupAll(".scroll-bar")) {
            if (!(node instanceof ScrollBar bar) || bar.getOrientation() != Orientation.VERTICAL) {
                continue;
            }
            if (!bar.isVisible() || bar.getMax() <= 0) return true;
            return bar.getValue() >= bar.getMax() - 1e-3;
        }
        return true;
    }

    /**
     * Keyboard scrolling, so a keyboard-only reader never needs the pointer. It moves by rows via
     * the list's public API rather than by writing the skin's scroll bar, which only owns the
     * position the list itself set.
     */
    private void scrollByKey(KeyCode code) {
        int last = timelineItems.size() - 1;
        if (last < 0) return;
        int top = firstVisibleRow();
        int page = Math.max(1, visibleItemCount() - 1);
        int target = switch (code) {
            case UP -> top - 1;
            case DOWN -> top + 1;
            case PAGE_UP -> top - page;
            case PAGE_DOWN -> top + page;
            case HOME -> 0;
            case END -> last;
            default -> top;
        };
        messageList.scrollTo((int) Math.clamp((long) target, 0, last));
    }

    /** Index of the first row whose cell is on screen, top to bottom. */
    private int firstVisibleRow() {
        int first = -1;
        for (Node node : messageList.lookupAll(".timeline-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) continue;
            Bounds bounds = messageList.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            if (bounds.getMaxY() <= 0) continue;
            first = first < 0 ? cell.getIndex() : Math.min(first, cell.getIndex());
        }
        return first < 0 ? 0 : first;
    }

    /** How many rows the viewport shows, which is what one page key should move. */
    private int visibleItemCount() {
        int count = 0;
        for (Node node : messageList.lookupAll(".timeline-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) count++;
        }
        return count;
    }

    /** One row of the timeline list: a day separator, a stored message or a queued one. */
    sealed interface TimelineItem
            permits TimelineItem.Day, TimelineItem.Bubble, TimelineItem.Pending {
        record Day(LocalDate day) implements TimelineItem { }

        record Bubble(CachedMessage message, boolean mine, boolean deleted) implements TimelineItem { }

        record Pending(PendingMessage pending) implements TimelineItem { }
    }

    /** Builds cell content on demand; the list recycles the cell, never the item. */
    private final class TimelineCell extends ListCell<TimelineItem> {
        TimelineCell() {
            getStyleClass().add("timeline-cell");
        }

        @Override
        protected void updateItem(TimelineItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(empty || item == null ? null : rowFor(item));
        }

        private Node rowFor(TimelineItem item) {
            if (item instanceof TimelineItem.Day day) return daySeparator(day.day());
            if (item instanceof TimelineItem.Pending pending) return pendingRow(pending.pending());
            TimelineItem.Bubble bubble = (TimelineItem.Bubble) item;
            Node row = messageRow(bubble.message(), bubble.mine(), bubble.deleted());
            if (bubble.message().id().equals(revealedMessageId)) {
                row.getStyleClass().add("message-highlight");
            }
            return row;
        }
    }

    private Node messageRow(CachedMessage message, boolean mine, boolean deleted) {
        String sender = senderName(message.senderId(), message.senderName(), message.conversationId());
        String time = messageTime.format(message.createdAt());
        Label meta = new Label(sender.isBlank() ? time : sender + " · " + time);
        meta.getStyleClass().add("message-meta");
        Label content = new Label(deleted ? i18n.get("shell.messageDeleted") : message.content());
        content.setWrapText(true);
        fitMessageText(content);
        HBox metaRow = new HBox(6, meta, UiComponents.spacer());
        metaRow.setAlignment(Pos.CENTER_LEFT);
        if (activeConversation != null
                && activeConversation.getType() == Conversation.TypeEnum.DIRECT && !deleted) {
            MenuButton menu = new MenuButton();
            menu.getStyleClass().add("icon-button");
            Icons.set(menu, Feather.MORE_HORIZONTAL);
            Icons.describe(menu, i18n.get("shell.messageActions"));
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

    /**
     * One display rule for the timeline and for search hits: your own message is "You", a group message
     * carries the member name its sync resolved, and a direct chat falls back to the counterpart's name.
     * An id is never shown as if it were a name; with nothing to show the caller prints the time alone.
     */
    private String senderName(UUID senderId, String storedName, UUID conversationId) {
        if (senderId != null && senderId.equals(context.userId())) return i18n.get("shell.messageYou");
        if (storedName != null && !storedName.isBlank()) return storedName;
        String counterpart = directCounterpartName(conversationId);
        return counterpart == null ? "" : counterpart;
    }

    private String directCounterpartName(UUID conversationId) {
        // Every timeline row belongs to the chat that is already open, so it is answered without a scan;
        // only a search hit can point at another conversation, and only then is the list walked.
        if (activeConversation != null && activeConversation.getId().equals(conversationId)) {
            return activeConversation.getType() == Conversation.TypeEnum.DIRECT
                    ? activeConversation.getName() : null;
        }
        for (Conversation conversation : conversationList.getItems()) {
            if (conversation.getId().equals(conversationId)
                    && conversation.getType() == Conversation.TypeEnum.DIRECT) {
                return conversation.getName();
            }
        }
        return null;
    }

    private Node pendingRow(PendingMessage pending) {
        Label meta = new Label(i18n.get("shell.sending"));
        meta.getStyleClass().add("message-meta");
        Label content = new Label(pending.content());
        content.setWrapText(true);
        fitMessageText(content);
        VBox bubble = new VBox(3, meta, content);
        bubble.getStyleClass().addAll("message-bubble", "mine", "pending");
        bubble.setMaxWidth(600);
        HBox row = new HBox(bubble);
        row.getStyleClass().add("message-row");
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    /**
     * Keeps a message body inside the timeline. A bubble is drawn at its preferred width, so a fixed
     * cap would let a narrow window clip the text instead of wrapping it; the cap follows the list.
     */
    private void fitMessageText(Label content) {
        content.maxWidthProperty().bind(
                javafx.beans.binding.Bindings.min(560.0, messageList.widthProperty().subtract(120)));
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
                    if (activeConversation != null
                            && activeConversation.getId().equals(conversation.getId())) {
                        // The hit is in the chat already open, where selecting the row that is
                        // already selected raises no change event; revealing it directly is what
                        // the user asked for, and reopening would only blank the timeline.
                        revealMessage(result.messageId());
                        return;
                    }
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
        int open = openGeneration.current();
        if (current.getType() == Conversation.TypeEnum.GROUP) {
            context.runAsync(() -> {
                context.groups().sendGroupMessage(current.getId(), text);
                context.groups().syncGroup(current.getId());
                return null;
            }, ignored -> refreshIfStillOpen(open, null), this::showStatusError);
            return;
        }
        try {
            context.messaging().queueMessage(current.getId(), text);
            refreshVisibleMessages(null, true);
        } catch (Exception exception) {
            showStatusError(exception);
            return;
        }
        context.runAsync(() -> {
            context.messaging().flushPendingMessages();
            context.messaging().syncConversation(current.getId());
            return null;
        }, ignored -> refreshIfStillOpen(open, null),
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
                    int open = openGeneration.current();
                    context.runAsync(() -> {
                        context.groups().addMember(current.getId(), selected.id());
                        context.groups().syncGroup(current.getId());
                        return null;
                    }, ignored -> {
                        status(i18n.get("shell.memberAdded"), true);
                        refreshIfStillOpen(open, null);
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
                    int open = openGeneration.current();
                    context.runAsync(() -> {
                        context.groups().removeMember(current.getId(), selected.id());
                        context.groups().syncGroup(current.getId());
                        return null;
                    }, ignored -> {
                        status(i18n.get("shell.memberRemoved"), true);
                        refreshIfStillOpen(open, null);
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
                // The list refresh re-selects this chat, and that is what brings its new name to the
                // header; setting the title here would write the name captured before the rename.
                this::refreshConversations,
                () -> {
                    conversationList.getItems().remove(current);
                    // Nothing still in flight for the deleted group may repaint the shell.
                    openGeneration.open();
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
                        .filter(message -> target.getId().equals(message.senderId()))
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
                .map(CachedMessage::senderId)
                .filter(java.util.Objects::nonNull)
                .filter(sender -> !context.userId().equals(sender))
                .findFirst().orElse(null);
    }

    private void deleteMessage(UUID messageId, boolean forEveryone) {
        Conversation current = activeConversation;
        if (current == null || context.messaging() == null) return;
        if (current.getType() != Conversation.TypeEnum.DIRECT) {
            // Second line of defence: a group's message rows have no actions menu, so nothing in the
            // UI reaches this line. It stays because the rule belongs to the operation, not to the
            // state of the control that offers it.
            status(i18n.get("shell.deleteDirectOnly"), false);
            return;
        }
        if (forEveryone && !ConfirmDialog.show(context.stage(), context.themeManager(),
                i18n.get("shell.deleteEveryoneTitle"), i18n.get("shell.deleteEveryoneMessage"),
                i18n.get("shell.deleteForEveryone"), i18n.get("dialog.cancel"), true)) {
            return;
        }
        status(i18n.get("shell.deletingMessage"), false);
        int open = openGeneration.current();
        context.runAsync(() -> {
            if (forEveryone) {
                context.messaging().deleteMessageForEveryone(current.getId(), messageId);
            } else {
                context.messaging().hideMessageForMe(current.getId(), messageId);
            }
            return null;
        }, ignored -> {
            refreshIfStillOpen(open, null);
            status(i18n.get("shell.messageDeletedStatus"), true);
        }, this::showStatusError);
    }

    private void clearHistory() {
        Conversation current = activeConversation;
        if (current == null || context.messaging() == null) return;
        if (current.getType() != Conversation.TypeEnum.DIRECT) {
            // A group disables the button that calls this, so the status is unreachable from the UI
            // and stays as the operation's own guard. ShellStateFxTest holds the visible half: the
            // button is disabled while a group is open.
            status(i18n.get("shell.clearDirectOnly"), false);
            return;
        }
        if (!ConfirmDialog.show(context.stage(), context.themeManager(),
                i18n.get("shell.clearHistoryTitle"), i18n.get("shell.clearHistoryMessage"),
                i18n.get("shell.clearHistory"), i18n.get("dialog.cancel"), true)) {
            return;
        }
        status(i18n.get("shell.clearingHistory"), false);
        int open = openGeneration.current();
        context.runAsync(() -> {
            context.messaging().clearHistory(current.getId());
            return null;
        }, ignored -> {
            refreshIfStillOpen(open, null);
            status(i18n.get("shell.historyCleared"), true);
        }, this::showStatusError);
    }

    private void setConversationActionsDisabled(boolean disabled) {
        reportButton.setDisable(disabled);
        groupSettingsButton.setDisable(disabled);
        clearHistoryButton.setDisable(disabled);
        addMemberButton.setDisable(disabled);
        removeMemberButton.setDisable(disabled);
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

    /**
     * The shell with no chat open, and the only writer of the header when there is nothing to show.
     * The whole empty state belongs here rather than at each call site: with no conversation, there is
     * nothing for the actions to act on, and a refresh that still saw one in {@code activeConversation}
     * could re-select a chat the shell has stopped showing.
     */
    private void showEmptyChat() {
        activeConversation = null;
        setConversationActionsDisabled(true);
        chatTitleLabel.setText(i18n.get("shell.noConversationTitle"));
        chatSubtitleLabel.setText(i18n.get("shell.noConversationHint"));
        resetTimelineState();
        timelineItems.clear();
        messageList.setPlaceholder(UiComponents.emptyState(
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
        Icons.themeToggle(themeButton, dark);
        Icons.describe(themeButton, i18n.get(dark ? "theme.toLight" : "theme.toDark"));
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
            String sender = senderName(item.senderId(), item.senderName(), item.conversationId());
            Label title = new Label(sender + ": " + preview(item.preview()));
            title.getStyleClass().add("conversation-name");
            Label meta = new Label(messageTime.format(item.createdAt()));
            meta.getStyleClass().add("conversation-meta");
            setGraphic(new VBox(2, title, meta));
            setText(null);
        }
    }
}
