package com.minishop.order;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/** Null and empty are valid here so that {@code @NotBlank} alone reports a missing phone. */
public final class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> {

    // [0-9], not \d: ASCII digits only.
    private static final Pattern VALID = Pattern.compile("0[0-9]{9}");
    private static final Pattern SEPARATORS = Pattern.compile("[ .\\-]");

    /** Removes spaces, dots and hyphens; this is the value that gets stored. */
    static String normalize(String raw) {
        return raw == null ? null : SEPARATORS.matcher(raw).replaceAll("");
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return VALID.matcher(normalize(value)).matches();
    }
}
