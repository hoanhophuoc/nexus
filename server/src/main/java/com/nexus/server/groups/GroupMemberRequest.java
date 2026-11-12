package com.nexus.server.groups;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GroupMemberRequest(@NotNull UUID userId) { }
