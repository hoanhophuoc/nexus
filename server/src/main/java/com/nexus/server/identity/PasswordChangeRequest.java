package com.nexus.server.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 12, max = 128) String newPassword) {
}
