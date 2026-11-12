package com.nexus.server.groups;

import com.nexus.server.messaging.ConversationPage;
import com.nexus.server.messaging.ConversationView;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class GroupService {
    private final GroupRepository groups;

    public GroupService(GroupRepository groups) {
        this.groups = groups;
    }

    public ConversationPage list(UUID userId, UUID cursor, int limit) {
        return groups.list(userId, cursor, Math.clamp(limit, 1, 100));
    }

    public ConversationView create(UUID creatorId, String name, List<UUID> invitedUserIds) {
        String normalizedName = name == null ? "" : name.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 120 || invitedUserIds == null
                || invitedUserIds.isEmpty() || invitedUserIds.size() > 100
                || invitedUserIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(invitedUserIds).size() != invitedUserIds.size()
                || invitedUserIds.contains(creatorId)) {
            throw new IllegalArgumentException("A group needs a name and at least one distinct invited user.");
        }
        return groups.create(creatorId, normalizedName, List.copyOf(invitedUserIds));
    }

    public void rename(UUID actorId, UUID groupId, String name) {
        String normalizedName = name == null ? "" : name.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("Group name must contain 1 to 120 characters.");
        }
        groups.rename(actorId, groupId, normalizedName);
    }

    public GroupMemberPage members(UUID actorId, UUID groupId, UUID cursor, int limit) {
        return groups.members(actorId, groupId, cursor, Math.clamp(limit, 1, 100));
    }

    public void addMember(UUID actorId, UUID groupId, UUID userId) {
        groups.addMember(actorId, groupId, userId);
    }

    public void removeMember(UUID actorId, UUID groupId, UUID userId) {
        groups.removeMember(actorId, groupId, userId);
    }

    public void setRole(UUID actorId, UUID groupId, UUID userId, GroupRoleRequest.Role role) {
        groups.setRole(actorId, groupId, userId, role.name());
    }
}
