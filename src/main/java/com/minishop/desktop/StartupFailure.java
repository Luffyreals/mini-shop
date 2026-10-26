package com.minishop.desktop;

import com.minishop.common.SafeExceptionLog;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Phân loại lỗi khi dựng context Spring lúc khởi động (QĐ7). Java thuần, không import JavaFX, để test bằng JUnit.
 * <p>
 * Chỉ một cửa sổ được mở, dựa vào khóa file của chính H2: lần mở thứ hai bị chặn ngay lúc Hikari mở kết nối
 * đầu tiên (mã lỗi 90020, {@code DATABASE_ALREADY_OPEN_1}), nên Flyway và seed không chạy và cửa sổ thứ nhất
 * không bị đụng tới.
 */
public enum StartupFailure {

    /** Một tiến trình Mini Shop khác đang giữ thư mục dữ liệu (AC-009-2). */
    ALREADY_RUNNING("Mini Shop đang chạy ở một cửa sổ khác."),

    /** Mọi lỗi khởi động còn lại: hộp thoại chung của AC-009-12. */
    OTHER(ErrorReporter.MESSAGE);

    /** Mã lỗi của H2 khi file cơ sở dữ liệu đã bị tiến trình khác mở. */
    static final int H2_DATABASE_ALREADY_OPEN = 90020;

    private static final int MAX_CAUSE_DEPTH = 20;

    private final String message;

    StartupFailure(String message) {
        this.message = message;
    }

    /** Câu hiện trong hộp thoại. */
    public String message() {
        return message;
    }

    /**
     * {@link #ALREADY_RUNNING} khi chuỗi {@code cause} (tối đa 20 cấp, không đi vòng vô hạn) có
     * {@link SQLException} mã 90020; ngược lại {@link #OTHER}.
     */
    public static StartupFailure classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            if (current instanceof SQLException sql && sql.getErrorCode() == H2_DATABASE_ALREADY_OPEN) {
                return ALREADY_RUNNING;
            }
            current = current.getCause();
        }
        return OTHER;
    }

    /**
     * Ghi log cho lỗi khởi động. {@link #ALREADY_RUNNING}: một dòng WARN, không stack trace và không đưa
     * thông điệp của H2. {@link #OTHER}: một sự kiện ERROR qua {@link SafeExceptionLog}, vì thông điệp H2 có
     * thể chứa cả dòng dữ liệu (ADR-0013).
     */
    public void log(Logger log, Throwable failure) {
        switch (this) {
            case ALREADY_RUNNING -> log.warn("Another Mini Shop process holds the data directory");
            case OTHER -> log.error("Mini Shop failed to start", SafeExceptionLog.withoutDatabaseMessages(failure));
        }
    }
}
