package com.minishop.cart;

import java.util.Optional;

/**
 * Chỗ truy cập giỏ của khách. Tách khỏi servlet API để service không phụ thuộc {@code HttpSession}
 * và test đơn vị dùng được bản trong bộ nhớ.
 */
interface GuestCartStore {

    /** Chỉ đọc: không bao giờ tạo session. */
    Optional<SessionCart> find();

    /** Chỉ gọi khi thật sự sắp ghi, vì có thể tạo session mới. */
    SessionCart getOrCreate();

    /** Ghi lại sau mỗi lần sửa (đúng cách khi session được phân tán). */
    void save(SessionCart cart);

    /** Xóa giỏ khỏi session. */
    void clear();
}
