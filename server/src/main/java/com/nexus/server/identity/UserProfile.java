package com.nexus.server.identity;

import java.time.LocalDate;
import java.util.UUID;

public record UserProfile(
        UUID id,
        String username,
        String fullName,
        String email,
        String address,
        LocalDate birthDate,
        String gender,
        String presence) {
}
