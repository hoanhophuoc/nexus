package com.nexus.server.groups;

import jakarta.validation.constraints.NotNull;

public record GroupRoleRequest(@NotNull Role role) {
    public enum Role { admin, member }
}
