package com.minishop.cart;

import java.util.Optional;

/**
 * Chỗ truy cập giỏ của khách. Giỏ khách nằm trong bộ nhớ của tiến trình (bản duy nhất là
 * {@code InMemoryGuestCartStore}, mất khi đóng app, BR-009-2). Tách thành interface để service không biết giỏ nằm
 * ở đâu và test đơn vị dùng được bản ghi lại lời gọi.
 */
interface GuestCartStore {

    /** Chỉ đọc: không bao giờ tạo giỏ. */
    Optional<SessionCart> find();

    /** Chỉ gọi khi thật sự sắp ghi, vì có thể tạo giỏ mới. */
    SessionCart getOrCreate();

    /** Ghi lại sau mỗi lần sửa. */
    void save(SessionCart cart);

    /** Xóa giỏ khách (sau khi gộp vào giỏ DB, hoặc khi đăng xuất). */
    void clear();
}
