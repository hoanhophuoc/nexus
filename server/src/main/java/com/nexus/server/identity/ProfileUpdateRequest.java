package com.nexus.server.identity;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ProfileUpdateRequest(
        @Size(min = 1, max = 120) @Pattern(regexp = ".*\\S.*") String fullName,
        @Email @Size(max = 254) String email,
        @Size(min = 1, max = 255) @Pattern(regexp = ".*\\S.*") String address,
        @Past LocalDate birthDate,
        @Pattern(regexp = "female|male|other|undisclosed") String gender) {

    @AssertTrue(message = "At least one profile field is required.")
    public boolean hasChanges() {
        return fullName != null || email != null || address != null || birthDate != null || gender != null;
    }
}
