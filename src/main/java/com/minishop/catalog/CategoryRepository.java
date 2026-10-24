package com.minishop.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Khóa dòng danh mục trước mọi thao tác có thể làm danh mục mất hoặc có thêm sản phẩm
     * (xóa danh mục, thêm/sửa sản phẩm), QĐ4, ADR-0010. Không có dòng thì trả rỗng.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id = :id")
    Optional<Category> lockById(@Param("id") Long id);

    /** Danh mục kèm số sản phẩm (cả đã ẩn) trong một câu SQL, cho trang quản trị. Chưa sắp xếp. */
    @Query("select new com.minishop.catalog.CategoryRow(c.id, c.name, c.description, count(p.id)) "
            + "from Category c left join Product p on p.category = c group by c.id, c.name, c.description")
    List<CategoryRow> findAllWithProductCount();
}
