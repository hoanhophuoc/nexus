package com.nexus.server.identity;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileUpdateRequestTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void acceptsAFieldThatOnlyOmitsOtherFields() {
        assertEquals(0, validator.validate(new ProfileUpdateRequest("  name  ", null, null, null, null)).size());
    }

    @Test
    void rejectsABlankFullName() {
        assertTrue(validator.validate(new ProfileUpdateRequest("   ", null, null, null, null)).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("fullNameNotBlank")));
    }

    @Test
    void rejectsABlankAddress() {
        assertTrue(validator.validate(new ProfileUpdateRequest(null, null, "\t", null, null)).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("addressNotBlank")));
    }

    @Test
    void reportsMissingFieldsThroughTheAssertions() {
        ProfileUpdateRequest empty = new ProfileUpdateRequest(null, null, null, null, null);

        assertFalse(empty.hasChanges());
        assertTrue(empty.isFullNameNotBlank());
        assertTrue(empty.isAddressNotBlank());
        assertTrue(validator.validate(empty).stream()
                .anyMatch(violation -> "At least one profile field is required.".equals(violation.getMessage())));
    }

    @Test
    void acceptsACompleteUpdate() {
        ProfileUpdateRequest request = new ProfileUpdateRequest("Name", "user@example.test", "Street 1",
                LocalDate.of(1990, 1, 1), "female");

        assertTrue(request.hasChanges());
        assertEquals(0, validator.validate(request).size());
    }
}
