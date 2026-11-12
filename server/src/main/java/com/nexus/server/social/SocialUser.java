package com.nexus.server.social;

import java.time.LocalDate;
import java.util.UUID;

public record SocialUser(UUID id, String username, String fullName, String address,
                         LocalDate birthDate, String gender, String presence) {
}
