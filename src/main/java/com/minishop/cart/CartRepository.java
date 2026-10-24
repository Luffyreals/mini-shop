package com.minishop.cart;

import com.minishop.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    /**
     * Khóa dòng {@code users} của chủ giỏ. Gọi đầu tiên trong mọi transaction ghi giỏ DB (ADR-0008),
     * để các thao tác ghi của cùng một người chạy tuần tự, kể cả khi người đó chưa có giỏ.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> lockOwner(@Param("userId") Long userId);

    /** Một câu SQL lấy giỏ, các dòng và sản phẩm (không N+1 khi open-in-view=false). */
    @EntityGraph(attributePaths = {"items", "items.product"})
    @Query("select c from Cart c where c.user.id = :userId")
    Optional<Cart> findWithItemsByUserId(@Param("userId") Long userId);

    @Query("select coalesce(sum(i.quantity), 0) from CartItem i where i.cart.user.id = :userId")
    long sumQuantityByUserId(@Param("userId") Long userId);

    /**
     * Xóa mọi dòng của giỏ người đó, giữ dòng {@code carts}. Chỉ gọi sau {@code lockOwner} trong cùng
     * transaction (ADR-0008). Là câu xóa hàng loạt nên không phụ thuộc entity đang được quản lý.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartItem i where i.cart.id in (select c.id from Cart c where c.user.id = :userId)")
    int deleteItemsByUserId(@Param("userId") Long userId);
}
