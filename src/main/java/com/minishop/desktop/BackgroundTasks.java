package com.minishop.desktop;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Chạy việc nặng (gọi service, đọc file) ngoài luồng giao diện, rồi trả kết quả về luồng giao diện.
 * Mọi màn hình gọi service qua interface này, không bao giờ trực tiếp (AC-009-11). Interface không import
 * JavaFX, nên presenter dùng nó được test bằng JUnit thường với một bản chạy đồng bộ.
 * <p>
 * Quy ước dùng: trước khi gọi {@code submit}, màn hình hiện trạng thái đang tải và khóa nút đã bắt đầu việc
 * (để bấm đúp không đặt hai đơn); mở lại trong {@code onSuccess}/{@code onFailure}.
 */
public interface BackgroundTasks {

    /**
     * Chạy {@code work} ngoài luồng giao diện. Khi xong, gọi {@code onSuccess} (hoặc {@code onFailure} nếu
     * {@code work} ném exception) <b>trên luồng giao diện</b>.
     * <p>
     * Lỗi mà không có {@code onFailure} ({@code null}), hoặc lỗi ném ra từ chính một callback, được chuyển
     * cho uncaught handler của luồng giao diện (hộp thoại lỗi chung, AC-009-12).
     *
     * @param onSuccess nhận kết quả của {@code work}; {@code null} nếu không cần
     * @param onFailure nhận exception của {@code work}; {@code null} để dùng bộ bắt lỗi chung
     * @return handle để hủy
     */
    <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess, Consumer<? super Throwable> onFailure);

    /** Như trên, không có {@code onFailure}: lỗi đi vào bộ bắt lỗi chung. */
    default <T> Handle submit(Callable<T> work, Consumer<? super T> onSuccess) {
        return submit(work, onSuccess, null);
    }

    /**
     * Tay cầm của một việc đang chạy. Hủy <b>không ngắt</b> luồng đang gọi service (một giao dịch đang chạy
     * vẫn tự commit hoặc rollback trọn vẹn); nó chỉ đảm bảo {@code onSuccess}/{@code onFailure} không bao
     * giờ được gọi, ví dụ khi người dùng đã chuyển sang màn khác.
     */
    interface Handle {

        /** Hủy callback. Gọi nhiều lần hay sau khi xong đều vô hại. Gọi từ luồng nào cũng được. */
        void cancel();

        boolean isCancelled();
    }
}
