package com.minishop.order;

import com.minishop.catalog.ProductRepository;

import java.util.Comparator;
import java.util.List;

/**
 * Một chỗ duy nhất cho "hủy đơn và hoàn kho", dùng chung cho khách và admin (ADR-0009).
 * Chỉ gọi sau khi đã khóa dòng {@code orders} trong cùng transaction.
 */
final class OrderCancellation {

    private OrderCancellation() {
    }

    /**
     * Chuyển đơn sang CANCELLED rồi cộng lại kho theo {@code product_id} tăng dần.
     *
     * @return số dòng hàng đã hoàn kho
     * @throws IllegalStateException nếu bước chuyển không hợp lệ hoặc một lần hoàn kho không cập nhật đúng 1 dòng
     */
    static int cancelAndRestoreStock(Order lockedOrder, ProductRepository products) {
        // 1. Copy (productId, quantity) now: incrementStock clears the persistence context.
        List<Restore> restores = lockedOrder.getItems().stream()
                .map(i -> new Restore(i.getProduct().getId(), i.getQuantity()))
                .sorted(Comparator.comparingLong(Restore::productId))
                .toList();

        // 2. Domain guard; the new status is flushed before the first incrementStock.
        lockedOrder.transitionTo(OrderStatus.CANCELLED);

        // 3. Give stock back in ascending product id. The context is cleared from the first call on.
        for (Restore r : restores) {
            if (products.incrementStock(r.productId(), r.quantity()) != 1) {
                throw new IllegalStateException("Stock restore failed for product " + r.productId());
            }
        }
        return restores.size();
    }

    private record Restore(long productId, int quantity) {
    }
}
