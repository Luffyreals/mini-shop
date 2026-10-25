package com.minishop.config;

import com.minishop.catalog.CategoryRepository;
import com.minishop.catalog.Product;
import com.minishop.catalog.ProductRepository;
import com.minishop.user.Role;
import com.minishop.user.User;
import com.minishop.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed trên PostgreSQL thật (AC-001-9, AC-001-10).
 * Context có thể đã tự chạy ApplicationRunner lúc khởi động (ngoài transaction của test), nên mỗi test
 * xóa sạch dữ liệu trong transaction của chính nó (được rollback) rồi gọi run() một cách tường minh.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DataInitializer.class, PasswordEncoderConfig.class})
@EnableConfigurationProperties(SeedProperties.class)
class DataInitializerTest {

    @Autowired
    DataInitializer initializer;
    @Autowired
    CategoryRepository categories;
    @Autowired
    ProductRepository products;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    SeedProperties seed;

    @BeforeEach
    void emptyDatabase() {
        products.deleteAllInBatch();
        categories.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    private void run() {
        initializer.run(new DefaultApplicationArguments());
    }

    @Test
    void run_onEmptyDatabase_seedsCatalogWithOutOfStockAndInactiveProducts() {
        run();

        assertThat(categories.count()).isEqualTo(5);
        assertThat(products.count()).isGreaterThanOrEqualTo(20);
        List<Product> all = products.findAll();
        assertThat(all).anyMatch(p -> p.getStock() == 0);
        assertThat(all).anyMatch(p -> !p.isActive());
        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void run_onEmptyDatabase_seedsUsersWithRolesAndBcryptHashes() {
        run();

        User admin = users.findByEmail("admin@minishop.local").orElseThrow();
        User user = users.findByEmail("user@minishop.local").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(seed.adminPassword());
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(seed.userPassword());
        assertThat(encoder.matches(seed.adminPassword(), admin.getPasswordHash())).isTrue();
        assertThat(encoder.matches(seed.userPassword(), user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong", admin.getPasswordHash())).isFalse();
    }

    @Test
    void run_twice_doesNotChangeCounts() {
        run();
        long categoryCount = categories.count();
        long productCount = products.count();
        long userCount = users.count();

        run();

        assertThat(categories.count()).isEqualTo(categoryCount);
        assertThat(products.count()).isEqualTo(productCount);
        assertThat(users.count()).isEqualTo(userCount);
    }

    @Test
    void run_whenOnlyUsersMissing_seedsUsersButNotCatalogAgain() {
        run();
        long productCount = products.count();
        users.deleteAllInBatch();

        run();

        assertThat(users.count()).isEqualTo(2);
        assertThat(categories.count()).isEqualTo(5);
        assertThat(products.count()).isEqualTo(productCount);
    }

    @Test
    void run_whenOnlyCatalogMissing_seedsCatalogButNotUsersAgain() {
        run();
        products.deleteAllInBatch();
        categories.deleteAllInBatch();

        run();

        assertThat(users.count()).isEqualTo(2);
        assertThat(categories.count()).isEqualTo(5);
        assertThat(products.count()).isGreaterThanOrEqualTo(20);
    }
}
