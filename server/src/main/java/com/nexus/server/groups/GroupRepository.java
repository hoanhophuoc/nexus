package com.nexus.server.groups;

import com.nexus.server.messaging.ConversationPage;
import com.nexus.server.messaging.ConversationView;

import java.util.List;
import java.util.UUID;

public interface GroupRepository {
    ConversationPage list(UUID userId, UUID cursor, int limit);
    ConversationView create(UUID creatorId, String name, List<UUID> invitedUserIds);
    void rename(UUID actorId, UUID groupId, String name);
    GroupMemberPage members(UUID actorId, UUID groupId, UUID cursor, int limit);
    void addMember(UUID actorId, UUID groupId, UUID userId);
    void removeMember(UUID actorId, UUID groupId, UUID userId);
    void setRole(UUID actorId, UUID groupId, UUID userId, String role);
}
