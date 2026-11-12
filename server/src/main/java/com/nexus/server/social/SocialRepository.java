package com.nexus.server.social;

import java.util.UUID;

public interface SocialRepository {
    UserPage<SocialUser> search(UUID viewerId, String query, UUID cursor, int limit);
    FriendRequestPage incomingRequests(UUID userId, UUID cursor, int limit);
    OutgoingFriendRequestPage outgoingRequests(UUID userId, UUID cursor, int limit);
    UserPage<SocialUser> friends(UUID userId, UUID cursor, int limit);
    UserPage<SocialUser> blocked(UUID userId, UUID cursor, int limit);
    void sendRequest(UUID requesterId, UUID targetId);
    void decideRequest(UUID userId, UUID requestId, boolean accept);
    void cancelRequest(UUID userId, UUID requestId);
    void removeFriend(UUID userId, UUID friendId);
    void block(UUID blockerId, UUID blockedId);
    void unblock(UUID blockerId, UUID blockedId);
    boolean canStartDirectConversation(UUID firstId, UUID secondId);
    UUID submitReport(UUID reporterId, ReportSubmission submission);
}
