package com.nexus.server.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MessageSubmission(@NotBlank @Size(max = 10000) String content) {
}
