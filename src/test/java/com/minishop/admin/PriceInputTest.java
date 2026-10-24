package com.minishop.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Price parsing (AC-006-20; BR-006-5): whole VND 1..999.999.999, one kind of thousands separator at most. */
class PriceInputTest {

    private static BigDecimal parsed(String raw) {
        return PriceInput.parse(raw).orElseThrow();
    }

    /** AC-006-20 */
    @ParameterizedTest
    @ValueSource(strings = {"1250000", "1.250.000", "1 250 000", " 1.250.000 ", "\t1250000\n"})
    void parse_threeAcceptedShapes_giveTheSameValueWithScaleZero(String raw) {
        BigDecimal price = parsed(raw);

        assertThat(price).isEqualTo(new BigDecimal("1250000"));
        assertThat(price.scale()).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1|1", "999|999", "1.000|1000", "12.345|12345", "999.999.999|999999999", "999999999|999999999",
            "1 000|1000", "10 000 000|10000000", "100|100", "1.000.000|1000000"})
    void parse_boundariesAndSmallValues(String raw, long expected) {
        assertThat(parsed(raw)).isEqualTo(BigDecimal.valueOf(expected));
    }

    /** BR-006-5 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "0", "00", "000", "-5", "-0", "+5", "12,5", "1,250,000", "1.5", "1.25", "abc",
            "1e6", "0x10", "1000000000", "1.000.000.000", "1 000 000 000", "9999999999", "99999999999999999999"})
    void parse_invalid_isEmpty(String raw) {
        assertThat(PriceInput.parse(raw)).isEmpty();
    }

    /** BR-006-5: groups must be exactly three digits, and the two separators are never mixed. */
    @ParameterizedTest
    @ValueSource(strings = {"1.25.000", "12.50.000", "1250.000", "1.2500.000", "1.250.00", "1.250.0000", ".250",
            "1.250.", "1..250", "1.250 000", "1 250.000", "1.250 000.000", "1 25 000", "1  250", "1 250  000",
            "1.250.000,", "1\u00A0250\u00A0000", "1_250_000", "1'250'000"})
    void parse_badGroupingOrMixedSeparators_isEmpty(String raw) {
        assertThat(PriceInput.parse(raw)).isEmpty();
    }

    @Test
    void parse_stripsOuterSpacesBeforeCheckingTheGroups() {
        assertThat(parsed("1 250 ")).isEqualTo(BigDecimal.valueOf(1250));
        assertThat(parsed("  1 250  ")).isEqualTo(BigDecimal.valueOf(1250));
    }

    @ParameterizedTest
    @ValueSource(strings = {"٣٤٥", "１２３", "1٠٠"})
    void parse_nonAsciiDigits_isEmpty(String raw) {
        assertThat(PriceInput.parse(raw)).isEmpty();
    }

    @Test
    void parse_exactlyAtTheUpperLimit_isAcceptedAndOneAboveIsNot() {
        assertThat(PriceInput.parse("999999999")).isPresent();
        assertThat(PriceInput.parse("1000000000")).isEmpty();
        assertThat(PriceInput.parse("999.999.999")).isPresent();
        assertThat(PriceInput.parse("1.000.000.000")).isEmpty();
    }

    /** BR-006-5: the first digit is 1-9, so leading zeros and a zero first group are invalid in every shape. */
    @ParameterizedTest
    @ValueSource(strings = {"007", "0001250000", "0000", "00999999999", "01", "0.001", "000.001", "0 001", "0.500",
            "0 500", "0.000", "0 000", "0.000.000", "0 000 000", "01.000", "01 000", "00.001"})
    void parse_leadingZerosOrZeroFirstGroup_isEmpty(String raw) {
        assertThat(PriceInput.parse(raw)).isEmpty();
    }

    @Test
    void parse_validShapesStillAcceptedAfterTheLeadingZeroRule() {
        assertThat(parsed("1.000")).isEqualTo(BigDecimal.valueOf(1000));
        assertThat(parsed("10 000")).isEqualTo(BigDecimal.valueOf(10_000));
        assertThat(parsed("999.999.999")).isEqualTo(BigDecimal.valueOf(999_999_999));
        assertThat(parsed("100.000")).isEqualTo(BigDecimal.valueOf(100_000));
        assertThat(parsed("1.000.050")).isEqualTo(BigDecimal.valueOf(1_000_050));
    }

    /** More than nine digits is out of range. */
    @Test
    void parse_tenDigits_isEmpty() {
        assertThat(PriceInput.parse("1000000000")).isEmpty();
    }
}
