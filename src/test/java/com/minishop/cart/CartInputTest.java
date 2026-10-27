package com.minishop.cart;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** QĐ8: số lượng trong giỏ đến dạng chuỗi thô, không bao giờ làm hỏng thao tác (AC-004-6, AC-004-7). */
class CartInputTest {

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
