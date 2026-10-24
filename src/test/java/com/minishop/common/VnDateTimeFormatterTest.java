package com.minishop.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-005-20: giờ hiển thị luôn là Asia/Ho_Chi_Minh, không phụ thuộc múi giờ của JVM. */
class VnDateTimeFormatterTest {

    private static final Instant INSTANT = Instant.parse("2026-10-24T17:30:00Z");

    private final TimeZone originalZone = TimeZone.getDefault();
    private final VnDateTimeFormatter formatter = new VnDateTimeFormatter();

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    void format_crossesMidnightInVietnamTime() {
        assertThat(formatter.format(INSTANT)).isEqualTo("25/10/2026 00:30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/New_York", "Asia/Tokyo"})
    void format_isIndependentOfJvmDefaultTimeZone(String zone) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));

        assertThat(formatter.format(INSTANT)).isEqualTo("25/10/2026 00:30");
    }

    @Test
    void format_padsDayMonthAndHour() {
        assertThat(formatter.format(Instant.parse("2026-01-02T00:05:00Z"))).isEqualTo("02/01/2026 07:05");
    }

    @Test
    void format_withNull_returnsEmptyString() {
        assertThat(formatter.format(null)).isEmpty();
    }
}
