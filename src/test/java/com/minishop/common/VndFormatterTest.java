package com.minishop.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Định dạng giá VND (AC-002-12). */
class VndFormatterTest {

    private final VndFormatter formatter = new VndFormatter();

    @Test
    void format_millions_usesDotGroupingAndSuffix() {
        assertThat(formatter.format(new BigDecimal("1250000"))).isEqualTo("1.250.000 ₫");
    }

    @Test
    void format_zero() {
        assertThat(formatter.format(BigDecimal.ZERO)).isEqualTo("0 ₫");
    }

    @Test
    void format_underThousand_hasNoSeparator() {
        assertThat(formatter.format(new BigDecimal("999"))).isEqualTo("999 ₫");
    }

    @Test
    void format_thousand() {
        assertThat(formatter.format(new BigDecimal("1000"))).isEqualTo("1.000 ₫");
    }

    @Test
    void format_null_returnsEmptyString() {
        assertThat(formatter.format(null)).isEmpty();
    }

    @Test
    void format_roundsHalfUpToWholeDong() {
        assertThat(formatter.format(new BigDecimal("1000.50"))).isEqualTo("1.001 ₫");
        assertThat(formatter.format(new BigDecimal("1000.49"))).isEqualTo("1.000 ₫");
    }

    @Test
    void format_usesPlainSpaceNotNbsp() {
        String result = formatter.format(new BigDecimal("1250000"));

        assertThat(result).doesNotContain(" ").doesNotContain(" ");
        assertThat(result.charAt(result.length() - 2)).isEqualTo(' ');
    }
}
