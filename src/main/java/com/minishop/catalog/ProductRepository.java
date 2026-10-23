package com.minishop.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Trừ kho nguyên tử (ADR-0005): chỉ trừ khi còn đủ hàng.
     *
     * @return 1 nếu đã trừ, 0 nếu không đủ hàng, số lượng không dương, hoặc không có sản phẩm;
     *         người gọi phải rollback khi trả 0
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :quantity "
            + "where p.id = :id and :quantity > 0 and p.stock >= :quantity")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);
}
