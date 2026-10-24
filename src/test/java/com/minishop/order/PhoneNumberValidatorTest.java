package com.minishop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Phone normalisation and validation (AC-007-16; BR-007-7). Plain JUnit, no Spring. */
class PhoneNumberValidatorTest {

    private final PhoneNumberValidator validator = new PhoneNumberValidator();

    /** BR-007-7: every accepted shape is stored as 0 + 9 digits. */
    @ParameterizedTest
    @ValueSource(strings = {"+84912345678", "+84 912 345 678", "+84-912-345-678", "+84.912.345.678", "84912345678",
            "84 912 345 678", "0912345678", "0912 345 678", "0912.345.678", "0912-345-678"})
    void normalize_acceptedForms_giveZeroAndNineDigits(String raw) {
        assertThat(PhoneNumberValidator.normalize(raw)).isEqualTo("0912345678");
        assertThat(validator.isValid(raw, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+84321234567", "+84 987 654 321", "84-355-123-456"})
    void normalize_otherMobilePrefixes_replaceOnlyTheCountryCode(String raw) {
        assertThat(PhoneNumberValidator.normalize(raw)).startsWith("0").hasSize(10);
        assertThat(validator.isValid(raw, null)).isTrue();
    }

    /** BR-007-7: the first digit after 84 must not be 0, and only Vietnam is accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"+840912345678", "840912345678", "+1 212 555 0100", "+8491234567", "849123456789",
            "+849123456789", "0912345", "abc", "09123456789", "091234567", "1912345678", "+84", "84", "+84 0",
            "++84912345678", "+84912345678x", "0abc456789"})
    void isValid_rejectedForms(String raw) {
        assertThat(validator.isValid(raw, null)).isFalse();
    }

    /** The two accepted lengths differ (10 and 11 digits), so "84" at the start of a 10-digit number is not a prefix. */
    @Test
    void normalize_tenDigitNumberStartingWith84_isNotTreatedAsInternational() {
        assertThat(PhoneNumberValidator.normalize("8412345678")).isEqualTo("8412345678");
        assertThat(new PhoneNumberValidator().isValid("8412345678", null)).isFalse();
    }

    /** Non-ASCII digits never count (ASCII only). */
    @Test
    void isValid_nonAsciiDigits_areRejected() {
        assertThat(validator.isValid("0٩١٢345678", null)).isFalse();
        assertThat(validator.isValid("+84٩١٢345678", null)).isFalse();
    }

    /** A rejected value is returned without separators, never rewritten into a valid-looking number. */
    @Test
    void normalize_rejectedForms_areOnlyStrippedOfSeparators() {
        assertThat(PhoneNumberValidator.normalize("+84 0912 345 678")).isEqualTo("+840912345678");
        assertThat(PhoneNumberValidator.normalize("+1 212 555 0100")).isEqualTo("+12125550100");
    }

    /** Null and empty are valid here so that {@code @NotBlank} alone reports a missing phone. */
    @ParameterizedTest
    @NullAndEmptySource
    void isValid_nullAndEmpty_areValidSoThatNotBlankReportsThem(String raw) {
        assertThat(validator.isValid(raw, null)).isTrue();
    }

    @Test
    void normalize_null_returnsNull() {
        assertThat(PhoneNumberValidator.normalize(null)).isNull();
    }
}
