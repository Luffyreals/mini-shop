package com.minishop.desktop;

import com.minishop.common.SafeExceptionLog;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cửa duy nhất để màn hình báo một lỗi không lường trước (AC-009-12) từ nơi đã bắt được nó, ví dụ trong
 * {@code onFailure} của {@link BackgroundTasks#submit}. Lỗi đi cùng đường với lỗi không ai bắt: một dòng ERROR
 * đã lọc thông điệp SQL (không dữ liệu cá nhân) và đúng một hộp thoại "Đã có lỗi xảy ra. Vui lòng thử lại.";
 * khi hộp thoại đang mở thì lỗi tiếp theo chỉ được ghi log.
 * <p>
 * <b>Quy ước cho {@code onFailure}:</b> chỉ tự xử lý các lỗi nghiệp vụ đã biết (ví dụ hết hàng, không tìm thấy
 * sản phẩm, sai mật khẩu): đổi trạng thái presenter, hiện toast hoặc thông báo dưới ô nhập. Mọi lỗi còn lại phải
 * được chuyển cho {@link #report(Throwable)}, không được nuốt, không tự {@code log} và không tự mở hộp thoại.
 * Để trống {@code onFailure} cũng tương đương (lỗi rơi vào uncaught handler), nhưng khi cần mở lại nút đang chờ
 * thì xử lý trạng thái trước rồi gọi {@code report}.
 * <p>
 * Gọi được từ mọi luồng (hộp thoại luôn dựng trên luồng giao diện); không cần JavaFX khi test, vì việc gửi đi
 * qua {@link Thread#getDefaultUncaughtExceptionHandler()} mà {@link MiniShopApp} đã đặt là
 * {@link UncaughtErrorHandler}.
 */
@Component
public final class UnexpectedErrors {

    private static final Logger LOG = LoggerFactory.getLogger(UnexpectedErrors.class);

    private final Supplier<Thread.UncaughtExceptionHandler> handlers;

    public UnexpectedErrors() {
        this(Thread::getDefaultUncaughtExceptionHandler);
    }

    UnexpectedErrors(Supplier<Thread.UncaughtExceptionHandler> handlers) {
        this.handlers = handlers;
    }

    /** Ghi log đã lọc và xin hiện hộp thoại lỗi chung. {@code error} {@code null} thì bỏ qua. */
    public void report(Throwable error) {
        if (error == null) {
            return;
        }
        Thread.UncaughtExceptionHandler handler = handlers.get();
        if (handler == null) {
            // Chưa có handler của app (ví dụ chạy ngoài MiniShopApp): vẫn không được nuốt lỗi.
            LOG.error("Unexpected error on thread {}", Thread.currentThread().getName(),
                    SafeExceptionLog.withoutDatabaseMessages(error));
            return;
        }
        handler.uncaughtException(Thread.currentThread(), error);
    }
}
