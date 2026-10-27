package com.minishop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Đầu vào thô của danh sách đơn hàng không bao giờ gây lỗi (AC-005-21). */
class OrderInputTest {

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
