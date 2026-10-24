package com.minishop.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Danh sách đơn của một khách (có phân trang). Chỉ đọc cột của {@code orders}, không chạm
     * {@code user} hay {@code items}, nên không có N+1. Người gọi truyền sort có {@code id} làm tie-breaker.
     */
    Page<Order> findByUserId(Long userId, Pageable pageable);

    /**
     * Chi tiết một đơn: đơn và các dòng hàng trong một câu SQL. Lọc theo {@code user_id} ngay trong
     * truy vấn nên đơn của người khác không bao giờ được tải (IDOR, QĐ8); trả rỗng thì coi là 404.
     */
    @EntityGraph(attributePaths = "items")
    @Query("select o from Order o where o.id = :id and o.user.id = :userId")
    Optional<Order> findDetailByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Khóa dòng {@code orders} (PESSIMISTIC_WRITE) của đúng chủ đơn. Phải được gọi đầu tiên trong mọi
     * transaction đổi trạng thái đơn, trước khi khóa {@code products} (QĐ6, ADR-0009). Yêu cầu thứ hai
     * chờ ở đây, rồi đọc lại trạng thái đã commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.user.id = :userId")
    Optional<Order> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** Mọi đơn cho admin, kèm {@code user} trong cùng câu SQL (không N+1). Câu count không join. */
    @EntityGraph(attributePaths = "user")
    Page<Order> findAllBy(Pageable pageable);

    /** Đơn theo trạng thái cho admin, kèm {@code user} trong cùng câu SQL. */
    @EntityGraph(attributePaths = "user")
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /** Chi tiết một đơn cho admin: đơn, các dòng hàng và người đặt trong một câu SQL. Không lọc chủ đơn. */
    @EntityGraph(attributePaths = {"items", "user"})
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findAdminDetailById(@Param("id") Long id);

    /**
     * Khóa dòng {@code orders} theo id, không lọc chủ đơn: chỉ {@code OrderAdminService} gọi. Đường của khách
     * luôn dùng {@link #lockByIdAndUserId} (IDOR). Cùng vai trò trong ADR-0009: gọi đầu tiên, trước khi khóa
     * {@code products}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);

    /** Số đơn theo trạng thái (một câu GROUP BY); trạng thái không có đơn thì không có dòng. */
    @Query("select new com.minishop.order.StatusTotal(o.status, count(o)) from Order o group by o.status")
    List<StatusTotal> countByStatus();

    /** Tổng tiền các đơn ở một trạng thái; 0 khi không có đơn. */
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status = :status")
    BigDecimal sumTotalByStatus(@Param("status") OrderStatus status);

    /** 5 đơn mới nhất kèm {@code user}: một câu có LIMIT, không có câu count (khác {@code Page}). */
    @EntityGraph(attributePaths = "user")
    List<Order> findTop5ByOrderByCreatedAtDescIdDesc();
}
