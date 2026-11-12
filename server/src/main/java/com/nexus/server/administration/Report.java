package com.nexus.server.administration;

import com.nexus.server.social.SocialUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Report(UUID id, SocialUser reporter, SocialUser reportedUser, String reason, Instant createdAt,
                     String status, List<ReportEvidence> selectedEvidence) {
}
