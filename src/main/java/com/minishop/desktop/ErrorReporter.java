package com.minishop.desktop;

import com.minishop.common.SafeExceptionLog;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phần quyết định của AC-009-12, không import {@code javafx.scene}: với mỗi lỗi không lường trước, ghi đúng
 * một sự kiện ERROR (tên luồng, chuỗi exception đã lọc thông điệp SQL, không dữ liệu cá nhân) và xin hiện một
 * hộp thoại. Khi một hộp thoại đang mở thì lỗi tiếp theo chỉ được ghi log, không mở thêm.
 * <p>
 * Việc vẽ hộp thoại do {@code Consumer<DialogRequest>} được tiêm vào đảm nhiệm ({@link ErrorDialog}); khi đóng
 * hộp thoại, bên vẽ phải gọi {@link DialogRequest#onClosed()}.
 */
final class ErrorReporter {

    /** Câu của AC-009-12. */
    static final String MESSAGE = "Đã có lỗi xảy ra. Vui lòng thử lại.";

    /** Yêu cầu hiện hộp thoại lỗi. {@code onClosed} phải được gọi khi hộp thoại đóng. */
    record DialogRequest(String message, Runnable onClosed) {
    }

    private final Consumer<DialogRequest> dialogSink;
    private final Logger log;
    private final AtomicBoolean dialogOpen = new AtomicBoolean();

    ErrorReporter(Consumer<DialogRequest> dialogSink) {
        this(dialogSink, LoggerFactory.getLogger(ErrorReporter.class));
    }

    ErrorReporter(Consumer<DialogRequest> dialogSink, Logger log) {
        this.dialogSink = Objects.requireNonNull(dialogSink, "dialogSink");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Gọi được từ mọi luồng. {@code error} {@code null} thì bỏ qua. */
    void report(String threadName, Throwable error) {
        if (error == null) {
            return;
        }
        log.error("Unexpected error on thread {}", threadName, SafeExceptionLog.withoutDatabaseMessages(error));
        if (!dialogOpen.compareAndSet(false, true)) {
            return;
        }
        try {
            dialogSink.accept(new DialogRequest(MESSAGE, () -> dialogOpen.set(false)));
        } catch (RuntimeException e) {
            // Không hiện được hộp thoại (ví dụ JavaFX chưa chạy): app vẫn sống, lỗi gốc đã nằm trong log.
            dialogOpen.set(false);
            log.warn("Could not show the error dialog", e);
        }
    }

    /** Có hộp thoại lỗi đang mở không (để test). */
    boolean isDialogOpen() {
        return dialogOpen.get();
    }
}
