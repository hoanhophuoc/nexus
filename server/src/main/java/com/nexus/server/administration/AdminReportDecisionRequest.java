package com.nexus.server.administration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminReportDecisionRequest(
        @NotNull @Pattern(regexp = "uphold|dismiss") String decision,
        @NotNull Boolean lockReportedUser,
        @Size(max = 1000) String note) {
}
