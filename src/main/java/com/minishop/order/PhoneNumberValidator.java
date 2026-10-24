package com.minishop.order;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Null and empty are valid here so that {@code @NotBlank} alone reports a missing phone. */
public final class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, String> {

    // [0-9], not \d: ASCII digits only.
    private static final Pattern VALID = Pattern.compile("0[0-9]{9}");
    private static final Pattern SEPARATORS = Pattern.compile("[ .\\-]");
    // International form: +84 or 84, then 9 digits whose first digit is not 0 ("+840912345678" is rejected).
    private static final Pattern INTERNATIONAL = Pattern.compile("\\+?84([1-9][0-9]{8})");

    /**
     * Removes spaces, dots and hyphens, then turns {@code +84xxxxxxxxx} or {@code 84xxxxxxxxx} into
     * {@code 0xxxxxxxxx} (BR-007-7). Anything else is returned without separators, so {@link #isValid} rejects it.
     * This is the value that gets stored. The two accepted shapes have different lengths (10 and 11 digits),
     * so they cannot be confused.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = SEPARATORS.matcher(raw).replaceAll("");
        Matcher international = INTERNATIONAL.matcher(stripped);
        return international.matches() ? "0" + international.group(1) : stripped;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return VALID.matcher(normalize(value)).matches();
    }
}
