package com.nexus.server.identity;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A partial profile update. The "not blank" rules are expressed as assertions instead of a
 * {@code .*\S.*} pattern: a pattern whose quantifiers can consume the same characters backtracks
 * super-linearly on input that never matches.
 */
public record ProfileUpdateRequest(
        @Size(min = 1, max = 120) String fullName,
        @Email @Size(max = 254) String email,
        @Size(min = 1, max = 255) String address,
        @Past LocalDate birthDate,
        @Pattern(regexp = "female|male|other|undisclosed") String gender) {

    @AssertTrue(message = "At least one profile field is required.")
    public boolean hasChanges() {
        return fullName != null || email != null || address != null || birthDate != null || gender != null;
    }

    @AssertTrue(message = "must not be blank")
    public boolean isFullNameNotBlank() {
        return fullName == null || !fullName.isBlank();
    }

    @AssertTrue(message = "must not be blank")
    public boolean isAddressNotBlank() {
        return address == null || !address.isBlank();
    }
}
