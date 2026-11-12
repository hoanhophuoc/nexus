package com.nexus.server.social;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ReportSubmission(
        @NotNull UUID reportedUserId,
        @NotBlank @Size(max = 2000) String reason,
        @NotNull Boolean evidenceConsent,
        @Size(max = 10) List<@Valid ReportEvidenceSubmission> selectedEvidence) {

    @AssertTrue(message = "Explicit consent is required when submitting message evidence.")
    public boolean evidenceConsentIsValid() {
        return selectedEvidence == null || selectedEvidence.isEmpty() || Boolean.TRUE.equals(evidenceConsent);
    }
}
