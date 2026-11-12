package com.nexus.server.groups;

import com.nexus.server.identity.SessionIdentity;
import com.nexus.server.messaging.ConversationPage;
import com.nexus.server.messaging.ConversationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {
    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    public ConversationPage list(@AuthenticationPrincipal SessionIdentity session,
                                 @RequestParam(required = false) UUID cursor,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return groups.list(session.userId(), cursor, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(@AuthenticationPrincipal SessionIdentity session,
                                   @Valid @RequestBody CreateGroupRequest request) {
        return groups.create(session.userId(), request.name(), request.invitedUserIds());
    }

    @PatchMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                       @Valid @RequestBody GroupNameRequest request) {
        groups.rename(session.userId(), groupId, request.name());
    }

    @GetMapping("/{groupId}/members")
    public GroupMemberPage members(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                                   @RequestParam(required = false) UUID cursor,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return groups.members(session.userId(), groupId, cursor, limit);
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public void addMember(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                          @Valid @RequestBody GroupMemberRequest request) {
        groups.addMember(session.userId(), groupId, request.userId());
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                             @PathVariable UUID userId) {
        groups.removeMember(session.userId(), groupId, userId);
    }

    @PutMapping("/{groupId}/members/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setRole(@AuthenticationPrincipal SessionIdentity session, @PathVariable UUID groupId,
                        @PathVariable UUID userId, @Valid @RequestBody GroupRoleRequest request) {
        groups.setRole(session.userId(), groupId, userId, request.role());
    }
}
