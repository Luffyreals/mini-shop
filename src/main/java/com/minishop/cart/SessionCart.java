package com.minishop.cart;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Giỏ hàng của khách vãng lai, lưu thẳng làm attribute của {@code HttpSession}.
 *
 * <ul>
 *   <li><b>Chỉ chứa id sản phẩm và số lượng.</b> Không có entity (sẽ bị serialize, lazy loading hỏng),
 *       không có giá hay tên (giá cũ sẽ bị dùng lại): trang giỏ luôn đọc dữ liệu hiện tại từ DB.</li>
 *   <li><b>Mọi method {@code synchronized}</b>: hai tab của cùng một khách dùng chung object này trong
 *       session, và {@link LinkedHashMap} có thể hỏng cấu trúc khi bị sửa đồng thời.</li>
 *   <li><b>{@link Serializable}</b>: session có thể được lưu ra đĩa hoặc phân tán; chỉ chứa kiểu đơn giản
 *       nên luôn serialize được.</li>
 * </ul>
 */
final class SessionCart implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String SESSION_ATTRIBUTE = "minishop.cart";

    /** id sản phẩm -> số lượng, giữ thứ tự thêm vào. */
    private final LinkedHashMap<Long, Integer> lines = new LinkedHashMap<>();

    /** @return số lượng của sản phẩm, 0 nếu không có trong giỏ */
    synchronized int quantityOf(Long productId) {
        return lines.getOrDefault(productId, 0);
    }

    /** Thêm dòng mới hoặc ghi đè số lượng (giữ vị trí cũ). */
    synchronized void put(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        lines.put(productId, quantity);
    }

    /** Không có dòng thì thôi. */
    synchronized void remove(Long productId) {
        lines.remove(productId);
    }

    /** @return bản sao không sửa được, theo thứ tự thêm vào */
    synchronized Map<Long, Integer> lines() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lines));
    }

    synchronized int totalQuantity() {
        int total = 0;
        for (int q : lines.values()) {
            total += q;
        }
        return total;
    }

    synchronized boolean isEmpty() {
        return lines.isEmpty();
    }
}
