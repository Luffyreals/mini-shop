package com.minishop.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
