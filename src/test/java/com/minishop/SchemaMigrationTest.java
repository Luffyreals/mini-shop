package com.minishop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-001-8: V1 chạy trên PostgreSQL thật, Hibernate validate đạt (context lên được) và các ràng buộc có hiệu lực.
 * Không dùng transaction của test: một lệnh lỗi làm hỏng transaction PostgreSQL, nên dữ liệu được dọn thủ công.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    long categoryId;
    long userId;
    long productId;
    long cartId;
    long orderId;

    @BeforeEach
    void insertParents() {
        categoryId = insertReturningId("select id from final table (insert into categories (name) values ('c'))");
        userId = insertReturningId("select id from final table (insert into users (email, password_hash, full_name, role) "
                + "values ('a@x.local', 'h', 'A', 'USER'))");
        productId = insertReturningId("select id from final table (insert into products (category_id, name, price, stock) "
                + "values (" + categoryId + ", 'p', 100, 5))");
        cartId = insertReturningId("select id from final table (insert into carts (user_id) values (" + userId + "))");
        orderId = insertReturningId("select id from final table (insert into orders (user_id, status, receiver_name, phone, shipping_address, "
                + "payment_method, total_amount) values (" + userId + ", 'PENDING', 'r', '1', 'addr', 'COD', 0))");
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("order_items", "orders", "cart_items", "carts", "products", "categories", "users")) {
            jdbc.update("delete from " + table);
        }
    }

    private long insertReturningId(String sql) {
        Long id = jdbc.queryForObject(sql, Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    @Test
    void migration_createsAllTablesAndRecordsV1() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "categories", "products", "carts", "cart_items", "orders",
                "order_items", "flyway_schema_history");

        Integer v1 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(v1).isEqualTo(1);
    }

    @Test
    void migration_recordsV2() {
        Integer v2 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success", Integer.class);
        assertThat(v2).isEqualTo(1);
    }

    @Test
    void users_uppercaseEmail_isRejected() {
        assertViolation("insert into users (email, password_hash, full_name, role) values ('A@x.local', 'h', 'B', 'USER')",
                "ck_users_email_lowercase");
    }

    @Test
    void users_emailWithSurroundingSpaces_isRejected() {
        assertViolation("insert into users (email, password_hash, full_name, role) values (' b@x.local ', 'h', 'B', 'USER')",
                "ck_users_email_lowercase");
    }

    @Test
    void products_negativeStock_isRejected() {
        assertViolation("insert into products (category_id, name, price, stock) values (" + categoryId + ", 'n', 1, -1)");
    }

    @Test
    void products_negativePrice_isRejected() {
        assertViolation("insert into products (category_id, name, price, stock) values (" + categoryId + ", 'n', -1, 1)");
    }

    @Test
    void users_duplicateEmail_isRejected() {
        assertViolation("insert into users (email, password_hash, full_name, role) values ('a@x.local', 'h', 'B', 'USER')");
    }

    @Test
    void users_unknownRole_isRejected() {
        assertViolation("insert into users (email, password_hash, full_name, role) values ('b@x.local', 'h', 'B', 'X')");
    }

    @Test
    void cartItems_zeroQuantity_isRejected() {
        assertViolation("insert into cart_items (cart_id, product_id, quantity) values ("
                + cartId + ", " + productId + ", 0)");
    }

    @Test
    void cartItems_duplicateCartAndProduct_isRejected() {
        jdbc.update("insert into cart_items (cart_id, product_id, quantity) values (?, ?, 1)", cartId, productId);
        assertViolation("insert into cart_items (cart_id, product_id, quantity) values ("
                + cartId + ", " + productId + ", 2)");
    }

    @Test
    void carts_secondCartForSameUser_isRejected() {
        assertViolation("insert into carts (user_id) values (" + userId + ")");
    }

    @Test
    void orders_unknownStatus_isRejected() {
        assertViolation(orderInsert("'X'", "'COD'"));
    }

    @Test
    void orders_unknownPaymentMethod_isRejected() {
        assertViolation(orderInsert("'PENDING'", "'X'"));
    }

    @Test
    void orderItems_negativeUnitPrice_isRejected() {
        assertViolation("insert into order_items (order_id, product_id, product_name, unit_price, quantity) values ("
                + orderId + ", " + productId + ", 'p', -1, 1)");
    }

    @Test
    void orderItems_zeroQuantity_isRejected() {
        assertViolation("insert into order_items (order_id, product_id, product_name, unit_price, quantity) values ("
                + orderId + ", " + productId + ", 'p', 1, 0)");
    }

    private String orderInsert(String status, String payment) {
        return "insert into orders (user_id, status, receiver_name, phone, shipping_address, payment_method, total_amount) "
                + "values (" + userId + ", " + status + ", 'r', '1', 'addr', " + payment + ", 0)";
    }

    private void assertViolation(String sql, String constraintName) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }

    private void assertViolation(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
