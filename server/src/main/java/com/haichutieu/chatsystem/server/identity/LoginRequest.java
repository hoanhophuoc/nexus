package com.haichutieu.chatsystem.server.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 32) String username, @NotBlank String password) {
}
