package com.haichutieu.chatsystem.server.identity;

import java.time.LocalDate;
import java.util.UUID;

public record NewUserRegistration(
        UUID id,
        String username,
        String usernameNormalized,
        String fullName,
        String email,
        String emailNormalized,
        String address,
        LocalDate birthDate,
        String gender,
        String passwordHash) {
}
