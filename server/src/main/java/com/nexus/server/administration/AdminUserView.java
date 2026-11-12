package com.nexus.server.administration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AdminUserView(UUID id, String username, String fullName, String email, String address,
                            LocalDate birthDate, String gender, String status, Instant createdAt) {
}
