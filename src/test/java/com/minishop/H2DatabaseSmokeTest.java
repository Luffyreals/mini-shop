package com.minishop;

import com.minishop.catalog.Product;
import com.minishop.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ứng dụng đầy đủ trên H2 (PostgreSQL mode), database duy nhất của dự án (ADR-0013): V1/V2 chạy, Hibernate validate
 * đạt, seed chạy được (AC-001-13).
 *
 * <p>Các luồng đặt hàng, hủy đơn, giỏ và quản trị trên H2 không còn nằm ở đây: từ ADR-0014 chúng được phủ bởi test
 * tích hợp của từng tính năng ({@code CartIntegrationTest}, {@code OrderIntegrationTest},
 * {@code OrderAdminIntegrationTest}, {@code ProductAdminIntegrationTest}, {@code CategoryAdminIntegrationTest}), vốn
 * đều chạy trên H2.
 */
@SpringBootTest
class H2DatabaseSmokeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProductRepository productRepository;

    @Test
    void contextLoads_withFlywayAndHibernateValidateOnH2() {
        assertThat(context).isNotNull();
    }

    /** V2 có hiệu lực trên H2. Lệnh insert bị từ chối nên không để lại dòng nào (seed vẫn đúng 2 người dùng). */
    @Test
    void v2_uppercaseEmail_isRejectedOnH2() {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where \"version\" = '2' and \"success\"", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "insert into users (email, password_hash, full_name, role) values ('A@x.local', 'h', 'A', 'USER')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * AC-008-6, 13 (ADR-0012, ADR-0013): every connection runs H2's {@code SET LOCK_TIMEOUT 5000} (milliseconds). If it were wrong, Hikari could not create a single connection and
     * the context would not start; this pins the value too. H2's {@code LOCK_TIMEOUT()} function reports the setting of
     * the current session, and the pool runs the init SQL on every physical connection.
     */
    @Test
    void connections_haveFiveSecondLockTimeoutOnH2() {
        assertThat(jdbc.queryForObject("select lock_timeout()", Integer.class)).isEqualTo(5000);
        assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET LOCK_TIMEOUT 5000");
    }

    @Test
    void seed_populatesProductsAndUsers() {
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isGreaterThanOrEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
    }

    /** AC-002-1, 2 trên H2: 22 sản phẩm seed trừ 1 sản phẩm bị ẩn còn 21 sản phẩm đang bán. */
    @Test
    void seed_hasTwentyOneActiveProducts() {
        assertThat(productRepository.findAll()).filteredOn(Product::isActive).hasSize(21);
    }
}
