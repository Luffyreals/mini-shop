package com.minishop.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Định dạng thời điểm theo giờ Việt Nam, ví dụ {@code 24/10/2026 14:05}.
 * <p>Không dùng {@code #temporals}: với {@code Instant} nó lấy múi giờ mặc định của JVM, mà container
 * Docker thường chạy UTC, nên giờ hiển thị sẽ lệch 7 tiếng tùy máy chạy. Ở đây múi giờ cố định.
 */
public class VnDateTimeFormatter {

    // DateTimeFormatter là immutable và thread-safe nên dùng chung một hằng.
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public String format(Instant instant) {
        return instant == null ? "" : FORMAT.format(instant);
    }
}
