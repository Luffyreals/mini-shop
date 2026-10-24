package com.minishop.cart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** QĐ8: tham số giỏ đến dạng chuỗi thô, không bao giờ làm hỏng request (AC-004-6, AC-004-7). */
class CartInputTest {

    @ParameterizedTest
    @ValueSource(strings = {"7", " 7 ", "0", "007", "999999999999999999"})
    void productId_withDigits_isParsed(String raw) {
        assertThat(CartInput.productId(raw)).contains(Long.valueOf(raw.strip()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "abc", "-1", "1.5", "99999999999999999999", "1000000000000000000", "+7", "7a"})
    void productId_withInvalidInput_isEmpty(String raw) {
        assertThat(CartInput.productId(raw)).isEmpty();
    }

    @Test
    void productId_trimsSurroundingWhitespace() {
        assertThat(CartInput.productId(" 7 ")).contains(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "99", " 5 ", "05", "10"})
    void quantity_inRange_isParsed(String raw) {
        assertThat(CartInput.quantity(raw)).hasValue(Integer.parseInt(raw.strip()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "00", "-1", "100", "abc", "", " ", "1e2", "+5", "1.0", "5 5", "٣"})
    void quantity_outOfRangeOrMalformed_isEmpty(String raw) {
        assertThat(CartInput.quantity(raw)).isEmpty();
    }
}
