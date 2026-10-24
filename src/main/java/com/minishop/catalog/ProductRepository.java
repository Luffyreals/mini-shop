package com.minishop.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * Danh sách theo Specification. {@code @EntityGraph} chỉ áp vào câu SELECT (không áp vào câu count),
     * và {@code category} là {@code @ManyToOne} nên LIMIT/OFFSET vẫn chạy trong SQL.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findByIdAndActiveTrue(Long id);

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

    /**
     * Cộng lại kho khi hủy đơn (BR-005-10). Không lọc {@code active}: sản phẩm đã ẩn vẫn được hoàn kho.
     * Là câu {@code @Modifying} riêng vì {@code Product.stock} là {@code updatable = false}.
     *
     * @return 1 nếu đã cộng, 0 nếu số lượng không dương hoặc không có sản phẩm
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :quantity where p.id = :id and :quantity > 0")
    int incrementStock(@Param("id") Long id, @Param("quantity") int quantity);
}
