package com.minishop.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw admin request parameters never become a 500 (AC-006-5, 34; BR-006-7). */
class AdminInputTest {

    // ---------- id ----------

    @Test
    void id_withDigits_parsesAndStrips() {
        assertThat(AdminInput.id("7")).contains(7L);
        assertThat(AdminInput.id(" 7 ")).contains(7L);
        assertThat(AdminInput.id("0")).contains(0L);
        assertThat(AdminInput.id("999999999999999999")).contains(999_999_999_999_999_999L);
    }

    /** AC-006-5: a bad id is a 404 for the controller. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "-1", "+1", "1.5", "7a", "99999999999999999999", "1000000000000000000",
            "٣", "0x10", "1e3", "7 7"})
    void id_withInvalidValue_isEmpty(String raw) {
        assertThat(AdminInput.id(raw)).isEmpty();
    }

    @Test
    void id_withTwentyDigits_isEmptyNotAnOverflow() {
        assertThat(AdminInput.id("1".repeat(20))).isEmpty();
    }

    // ---------- stockDelta ----------

    /** AC-006-32, 33 */
    @ParameterizedTest
    @CsvSource({"5, 5", "+5, 5", "-2, -2", "100000, 100000", "-100000, -100000",
            "+100000, 100000", "000005, 5", "1, 1", "-1, -1", "99999, 99999"})
    void stockDelta_withValidValue_parses(String raw, int expected) {
        assertThat(AdminInput.stockDelta(raw)).contains(expected);
    }

    /** AC-006-34: the boundary is exactly +/-100.000. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "0", "+0", "-0", "000000", "abc", "1.5", "1,5", "100001", "-100001", "+-1", "-+1",
            "--1", "++1", "1000000", "-1000000", "99999999999", "5a", "a5", "5 5", "- 5", "+", "-", "١٢", "１２",
            "1e3", "0x10", "٣"})
    void stockDelta_withInvalidValue_isEmpty(String raw) {
        assertThat(AdminInput.stockDelta(raw)).isEmpty();
    }

    @Test
    void stockDelta_stripsOuterSpaces() {
        assertThat(AdminInput.stockDelta(" 5 ")).contains(5);
        assertThat(AdminInput.stockDelta(" -2 ")).contains(-2);
    }

    @Test
    void stockDelta_rejectsTheValuesJustOutsideTheBoundary() {
        assertThat(AdminInput.stockDelta("100000")).isPresent();
        assertThat(AdminInput.stockDelta("100001")).isEmpty();
        assertThat(AdminInput.stockDelta("-100000")).isPresent();
        assertThat(AdminInput.stockDelta("-100001")).isEmpty();
    }

    // ---------- page ----------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "0", "-3", "1.5", "99999999999999999999", "   ", "+2", "١"})
    void page_withInvalidValue_isOne(String raw) {
        assertThat(AdminInput.page(raw)).isEqualTo(1);
    }

    @Test
    void page_withValidValue_isParsedAndClampedBetweenOneAndTenThousand() {
        assertThat(AdminInput.page("1")).isEqualTo(1);
        assertThat(AdminInput.page("2")).isEqualTo(2);
        assertThat(AdminInput.page(" 3 ")).isEqualTo(3);
        assertThat(AdminInput.page("9999")).isEqualTo(9_999);
        assertThat(AdminInput.page("10000")).isEqualTo(10_000);
        assertThat(AdminInput.page("10001")).isEqualTo(10_000);
        assertThat(AdminInput.page("999999")).isEqualTo(10_000);
        assertThat(AdminInput.page("999999999")).isEqualTo(10_000);
    }

    @Test
    void page_withTenDigits_isPageOneNotAnOverflow() {
        assertThat(AdminInput.page("1000000000")).isEqualTo(1);
    }
}
