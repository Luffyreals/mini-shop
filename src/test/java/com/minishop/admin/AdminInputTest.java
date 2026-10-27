package com.minishop.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw text typed in the admin stock field never becomes an unexpected error (AC-006-5, 34; BR-006-7). */
class AdminInputTest {

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

    /** D6 B2: a pasted special space no longer makes a valid number fail (FormText.clean first). */
    @Test
    void stockDelta_withNoBreakSpaceAfterTheNumber_parses() {
        assertThat(AdminInput.stockDelta("5\u00A0")).contains(5);
    }

    @Test
    void stockDelta_withNarrowNoBreakSpaceBeforeTheNumber_parses() {
        assertThat(AdminInput.stockDelta("\u202F-2")).contains(-2);
    }

    @Test
    void stockDelta_withFigureSpaceAroundASignedNumber_parses() {
        assertThat(AdminInput.stockDelta("\u2007+5")).contains(5);
        assertThat(AdminInput.stockDelta("+5\u2007")).contains(5);
    }

    @Test
    void stockDelta_withInvisibleCharacterInTheMiddle_dropsItAndParses() {
        assertThat(AdminInput.stockDelta("5\u200B0")).contains(50);
    }

    @Test
    void stockDelta_withSpecialSpaceInsideTheNumber_isStillRejected() {
        // a special space becomes a plain space, and a space inside the digits is not a separator we accept
        assertThat(AdminInput.stockDelta("5\u00A05")).isEmpty();
    }

    @Test
    void stockDelta_rejectsThousandsSeparators() {
        assertThat(AdminInput.stockDelta("1.000")).isEmpty();
        assertThat(AdminInput.stockDelta("1 000")).isEmpty();
        assertThat(AdminInput.stockDelta("1\u00A0000")).isEmpty();
    }

    @Test
    void stockDelta_rejectsTheValuesJustOutsideTheBoundary() {
        assertThat(AdminInput.stockDelta("100000")).isPresent();
        assertThat(AdminInput.stockDelta("100001")).isEmpty();
        assertThat(AdminInput.stockDelta("-100000")).isPresent();
        assertThat(AdminInput.stockDelta("-100001")).isEmpty();
    }
}
