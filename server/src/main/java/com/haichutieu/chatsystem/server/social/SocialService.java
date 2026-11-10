package com.haichutieu.chatsystem.server.social;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SocialService {

    private final SocialRepository social;
    private final PresenceStore presence;

    public SocialService(SocialRepository social, PresenceStore presence) {
        this.social = social;
        this.presence = presence;
    }

    public UserPage<SocialUser> search(UUID viewerId, String query, UUID cursor, int limit) {
        String normalizedQuery = query.strip();
        if (normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("Search query cannot be empty.");
        }
        return withPresence(social.search(viewerId, normalizedQuery, cursor, Math.clamp(limit, 1, 100)));
    }

    public FriendRequestPage incomingRequests(UUID userId, UUID cursor, int limit) {
        FriendRequestPage page = social.incomingRequests(userId, cursor, Math.clamp(limit, 1, 100));
        if (page.items() == null || page.items().isEmpty()) return page;
        Set<UUID> online = presence.onlineAmong(page.items().stream()
                .map(request -> request.from().id()).toList());
        if (online.isEmpty()) return page;
        List<IncomingFriendRequest> items = page.items().stream()
                .map(request -> new IncomingFriendRequest(request.id(),
                        withPresence(request.from(), online.contains(request.from().id()))))
                .toList();
        return new FriendRequestPage(items, page.nextCursor());
    }

    public OutgoingFriendRequestPage outgoingRequests(UUID userId, UUID cursor, int limit) {
        OutgoingFriendRequestPage page = social.outgoingRequests(userId, cursor, Math.clamp(limit, 1, 100));
        if (page.items() == null || page.items().isEmpty()) return page;
        Set<UUID> online = presence.onlineAmong(page.items().stream()
                .map(request -> request.to().id()).toList());
        if (online.isEmpty()) return page;
        List<OutgoingFriendRequest> items = page.items().stream()
                .map(request -> new OutgoingFriendRequest(request.id(),
                        withPresence(request.to(), online.contains(request.to().id()))))
                .toList();
        return new OutgoingFriendRequestPage(items, page.nextCursor());
    }

    public UserPage<SocialUser> friends(UUID userId, UUID cursor, int limit) {
        return withPresence(social.friends(userId, cursor, Math.clamp(limit, 1, 100)));
    }

    public UserPage<SocialUser> blocked(UUID userId, UUID cursor, int limit) {
        return withPresence(social.blocked(userId, cursor, Math.clamp(limit, 1, 100)));
    }

    public void sendRequest(UUID requesterId, UUID targetId) {
        requireOtherUser(requesterId, targetId);
        social.sendRequest(requesterId, targetId);
    }

    public void decideRequest(UUID userId, UUID requestId, FriendRequestDecision.Decision decision) {
        social.decideRequest(userId, requestId, decision == FriendRequestDecision.Decision.accept);
    }

    public void cancelRequest(UUID userId, UUID requestId) {
        social.cancelRequest(userId, requestId);
    }

    public void removeFriend(UUID userId, UUID friendId) {
        requireOtherUser(userId, friendId);
        social.removeFriend(userId, friendId);
    }

    public void block(UUID blockerId, UUID blockedId) {
        requireOtherUser(blockerId, blockedId);
        social.block(blockerId, blockedId);
    }

    public void unblock(UUID blockerId, UUID blockedId) {
        requireOtherUser(blockerId, blockedId);
        social.unblock(blockerId, blockedId);
    }

    public boolean canStartDirectConversation(UUID firstId, UUID secondId) {
        return !firstId.equals(secondId) && social.canStartDirectConversation(firstId, secondId);
    }

    public UUID submitReport(UUID reporterId, ReportSubmission submission) {
        requireOtherUser(reporterId, submission.reportedUserId());
        if (submission.selectedEvidence() != null && !submission.selectedEvidence().isEmpty()
                && !Boolean.TRUE.equals(submission.evidenceConsent())) {
            throw new IllegalArgumentException("Explicit consent is required for submitted evidence.");
        }
        ReportSubmission normalized = new ReportSubmission(submission.reportedUserId(),
                submission.reason().strip(), submission.evidenceConsent(),
                submission.selectedEvidence() == null ? null : List.copyOf(submission.selectedEvidence()));
        return social.submitReport(reporterId, normalized);
    }

    private UserPage<SocialUser> withPresence(UserPage<SocialUser> page) {
        if (page == null || page.items() == null || page.items().isEmpty()) return page;
        Set<UUID> online = presence.onlineAmong(page.items().stream().map(SocialUser::id).toList());
        if (online.isEmpty()) return page;
        List<SocialUser> items = page.items().stream()
                .map(user -> withPresence(user, online.contains(user.id())))
                .toList();
        return new UserPage<>(items, page.nextCursor());
    }

    private static SocialUser withPresence(SocialUser user, boolean online) {
        return new SocialUser(user.id(), user.username(), user.fullName(), user.address(),
                user.birthDate(), user.gender(), online ? "online" : "offline");
    }

    private static void requireOtherUser(UUID userId, UUID otherId) {
        if (userId.equals(otherId)) {
            throw new SocialConflictException();
        }
    }
}
