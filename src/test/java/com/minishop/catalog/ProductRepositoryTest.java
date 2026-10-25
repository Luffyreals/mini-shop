package com.minishop.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Trừ kho nguyên tử trên PostgreSQL thật (ADR-0005, AC-001-11, AC-001-12). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryTest {

    @Autowired
    ProductRepository products;

    @Autowired
    CategoryRepository categories;

    @Autowired
    JdbcTemplate jdbc;

    private Long saveProduct(int stock) {
        Category category = categories.save(new Category("Cat " + System.nanoTime(), null));
        return products.saveAndFlush(new Product(category, "P", null, BigDecimal.valueOf(1000), stock)).getId();
    }

    @Test
    void decrementStock_whenEnough_returnsOneAndReadBackShowsNewStock() {
        Long id = saveProduct(5);

        int updated = products.decrementStock(id, 3);

        assertThat(updated).isEqualTo(1);
        // findById thấy 2 chứng tỏ clearAutomatically đã xóa bản cũ (stock = 5) khỏi persistence context
        assertThat(products.findById(id).orElseThrow().getStock()).isEqualTo(2);
    }

    @Test
    void decrementStock_whenInsufficient_returnsZeroAndStockUnchanged() {
        Long id = saveProduct(2);

        int updated = products.decrementStock(id, 3);

        assertThat(updated).isZero();
        assertThat(products.findById(id).orElseThrow().getStock()).isEqualTo(2);
    }

    @Test
    void decrementStock_whenExactlyEnough_returnsOneAndStockIsZero() {
        Long id = saveProduct(3);

        int updated = products.decrementStock(id, 3);

        assertThat(updated).isEqualTo(1);
        assertThat(products.findById(id).orElseThrow().getStock()).isZero();
    }

    @Test
    void decrementStock_whenProductMissing_returnsZero() {
        assertThat(products.decrementStock(-1L, 1)).isZero();
    }

    @Test
    void decrementStock_whenQuantityNotPositive_returnsZeroAndStockUnchanged() {
        Long id = saveProduct(5);

        assertThat(products.decrementStock(id, 0)).isZero();
        assertThat(products.decrementStock(id, -5)).isZero();
        assertThat(stockInDb(id)).isEqualTo(5);
    }

    /**
     * Admin load sản phẩm (tồn kho 10), khách đặt hàng trừ 3, rồi admin lưu thay đổi khác.
     * Tồn kho phải còn 7: lần lưu của admin không được ghi đè lượng vừa trừ ({@code stock} là updatable = false).
     */
    @Test
    void savingLoadedProduct_afterDecrement_doesNotOverwriteStock() {
        Long id = saveProduct(10);
        Product loadedByAdmin = products.findById(id).orElseThrow();

        products.decrementStock(id, 3);
        loadedByAdmin.setActive(false);
        products.saveAndFlush(loadedByAdmin);

        assertThat(stockInDb(id)).isEqualTo(7);
    }

    private Integer stockInDb(Long id) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, id);
    }
}
