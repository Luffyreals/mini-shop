package com.minishop.desktop;

import javafx.scene.Node;

/**
 * Dựng màn hình cho một loại {@link Route}. Mỗi tính năng khai báo factory của mình thành bean Spring;
 * {@link Navigator} chọn factory theo kiểu chính xác của route. Hai factory cùng kiểu route là lỗi cấu hình.
 * <p>
 * {@link #create} chạy trên luồng giao diện, nên chỉ dựng node và gắn presenter; việc gọi service phải
 * đi qua {@link BackgroundTasks} (hiện trạng thái đang tải trước, rồi cập nhật khi xong).
 *
 * @param <R> kiểu route mà factory này phục vụ
 */
public interface ScreenFactory<R extends Route> {

    /** Kiểu route chính xác mà factory này dựng màn cho. */
    Class<R> routeType();

    /** Dựng màn mới cho route. Mỗi lần gọi trả về một cây node mới. */
    Node create(R route);
}
