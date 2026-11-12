package com.nexus.server.social;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReportEvidenceSubmission(
        @NotNull UUID messageId,
        @NotBlank @Size(max = 4000) String submittedContent) {
}
