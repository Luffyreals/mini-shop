package com.minishop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Đầu vào thô của các endpoint đơn hàng không bao giờ thành 500 (AC-005-21, 24; BR-005-5). */
class OrderInputTest {

    @Test
    void orderId_withDigits_parsesAndStrips() {
        assertThat(OrderInput.orderId("7")).contains(7L);
        assertThat(OrderInput.orderId(" 7 ")).contains(7L);
        assertThat(OrderInput.orderId("999999999999999999")).contains(999_999_999_999_999_999L);
    }

    /** AC-005-24 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "-1", "1.5", "99999999999999999999", "1000000000000000000", "٣", "7a"})
    void orderId_withInvalidValue_isEmpty(String raw) {
        assertThat(OrderInput.orderId(raw)).isEmpty();
    }

    /** BR-005-5 */
    @Test
    void amount_withDigits_parsesAndStrips() {
        assertThat(OrderInput.amount("450000")).contains(new BigDecimal("450000"));
        assertThat(OrderInput.amount(" 450000 ")).contains(new BigDecimal("450000"));
        assertThat(OrderInput.amount("999999999999999")).contains(new BigDecimal("999999999999999"));
    }

    /** BR-005-5 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "450000.5", "-1", "abc", "4.5E+5", "1234567890123456"})
    void amount_withInvalidValue_isEmpty(String raw) {
        assertThat(OrderInput.amount(raw)).isEmpty();
    }

    /** AC-005-21 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "0", "-3", "1.5", "99999999999999999999"})
    void page_withInvalidValue_isOne(String raw) {
        assertThat(OrderInput.page(raw)).isEqualTo(1);
    }

    @Test
    void page_withValidValue_isParsedAndClamped() {
        assertThat(OrderInput.page("2")).isEqualTo(2);
        assertThat(OrderInput.page(" 3 ")).isEqualTo(3);
        assertThat(OrderInput.page("999999")).isEqualTo(10_000);
        assertThat(OrderInput.page("10000")).isEqualTo(10_000);
    }
}
