package com.nexus.server.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmation(
        @NotBlank @Size(min = 32, max = 128) String token,
        @NotBlank @Size(min = 12, max = 128) String newPassword) {
}
