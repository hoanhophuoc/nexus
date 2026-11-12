package com.nexus.server.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegistrationRequest(
        @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "[A-Za-z0-9_.-]+") String username,
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 255) String address,
        @NotNull @Past LocalDate birthDate,
        @NotBlank @Pattern(regexp = "female|male|other|undisclosed") String gender,
        @NotBlank @Size(min = 12, max = 128) String password) {
}
