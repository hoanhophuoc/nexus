package com.haichutieu.chatsystem.server.groups;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateGroupRequest(@NotBlank @Size(max = 120) String name,
                                 @NotEmpty @Size(max = 100) List<@NotNull UUID> invitedUserIds) { }
