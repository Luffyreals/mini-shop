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

import java.util.List;
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

    /**
     * Admin điều chỉnh kho theo lượng ±, nguyên tử (ADR-0010), đối xứng với {@link #decrementStock}: điều kiện
     * nằm trong {@code WHERE} nên không có bước "đọc rồi ghi". Không lọc {@code active}: sản phẩm đã ẩn vẫn
     * điều chỉnh được. Chỉ {@code ProductAdminService} được gọi.
     *
     * @return 1 nếu đã đổi; 0 nếu {@code delta} bằng 0, kho mới nằm ngoài 0..100000, hoặc không có sản phẩm
     *         (người gọi đọc lại để phân loại)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :delta "
            + "where p.id = :id and :delta <> 0 and p.stock + :delta between 0 and 100000")
    int adjustStock(@Param("id") Long id, @Param("delta") int delta);

    /** Số sản phẩm (cả đã ẩn) của một danh mục; xóa danh mục chỉ được khi bằng 0. */
    long countByCategoryId(Long categoryId);

    /** Sản phẩm đang bán sắp hết hàng, ít nhất trước, tối đa 10 dòng (một câu SQL có LIMIT). */
    List<Product> findTop10ByActiveTrueAndStockLessThanEqualOrderByStockAscIdAsc(int maxStock);

    /**
     * Trang chủ (QĐ5, D3): 3 sản phẩm đang bán mới nhất, cùng thứ tự với sắp xếp "Mới nhất" của danh sách
     * ({@code createdAt} giảm, {@code id} giảm làm tie-breaker). Phần tử đầu là thẻ lớn, hai phần tử sau là
     * "hàng mới". Một câu SQL có LIMIT; {@code @EntityGraph} nạp danh mục cùng lúc nên không có N+1.
     */
    @EntityGraph(attributePaths = "category")
    List<Product> findTop3ByActiveTrueOrderByCreatedAtDescIdDesc();

    /**
     * Trang chủ (QĐ5, D3): tối đa 3 sản phẩm đang bán có tồn kho trong khoảng {@code [minStock, maxStock]},
     * ít nhất trước, rồi theo {@code id}. Người gọi truyền {@code (1, LOW_STOCK_THRESHOLD)}: tồn 0 là "Hết hàng",
     * không phải "sắp hết". Không nạp danh mục vì khối này chỉ hiện tên và số lượng còn lại.
     */
    List<Product> findTop3ByActiveTrueAndStockBetweenOrderByStockAscIdAsc(int minStock, int maxStock);
}
