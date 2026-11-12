package com.nexus.server.administration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUserLockRequest(@NotNull Boolean locked, @NotBlank @Size(max = 1000) String reason) {
}
